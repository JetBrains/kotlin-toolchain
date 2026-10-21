/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.compose.reload

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.Model
import org.jetbrains.amper.frontend.dr.resolver.AmperResolutionSettings
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencies.Companion.moduleDependencies
import org.jetbrains.amper.frontend.getComposeHotReloadVersion
import org.jetbrains.amper.fswatching.watchPaths
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.compose.reload.DelicateHotReloadApi
import org.jetbrains.compose.reload.core.awaitOrThrow
import org.jetbrains.compose.reload.orchestration.OrchestrationClientRole
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage.ReloadClassesRequest.ChangeType
import org.jetbrains.compose.reload.orchestration.OrchestrationMessageId
import org.jetbrains.compose.reload.orchestration.OrchestrationServer
import org.jetbrains.compose.reload.orchestration.asFlow
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Handles the execution of a Compose Hot Reload loop, enabling what in application code to be reloaded
 * incrementally without restarting the entire application. The hot reload loop operates by tracking
 * what, rebuilding application modules, and coordinating with a hot-reload agent to apply updates.
 *
 * @property delegate A [HotReloadDelegate] implementation that handles operations such as reading the model,
 * building and running the application, and performing incremental rebuilds.
 */
class HotReloadLoop<Context : HotReloadProjectContext> private constructor(
    private val delegate: HotReloadDelegate<Context>,
) {
    private val orchestration = OrchestrationServer()

    // Completed when the server receives the app connection.
    private val appOnlineMark = CompletableDeferred<Unit>()

    private val rebuildEventsFlow = MutableSharedFlow<RebuildEvent>()

    // `null` here means no valid state. Caused by errors during model reloads.
    private val stateFlow = MutableStateFlow<InternalState<Context>?>(null)

    private val validStateFlow = stateFlow
        .mapNotNull { it?.state }

    /** Incremental info to pass to the hot reload agent to reload the changed classes */
    typealias ClasspathChanges = List<IncrementalCache.Change>

    private enum class LoopResult {
        /** App exited - hot reload should exit as well */
        Exit,
        /** Dev-tools requested the app restart explicitly. Must restart the whole [HotReloadLoop]. */
        Restart,
    }

    /** Current hot reload loop state */
    data class State<Context : HotReloadProjectContext>(
        val cliContext: Context,
        val model: Model,
        /** App module under hot reload */
        val hotApp: AmperModule,
    ) {
        val hotAppDependencies by lazy {
            with(cliContext) { hotApp.moduleDependencies(AmperResolutionSettings(userCacheRoot, incrementalCache, openTelemetry)) }
        }
    }

    private class RestartException : Exception()

    companion object {
        /**
         * Main public entry point into [HotReloadLoop].
         */
        suspend fun run(delegate: HotReloadDelegate<*>) {
            var result: LoopResult
            do {
                result = HotReloadLoop(delegate).runLoop()
            } while (result == LoopResult.Restart)
        }
    }

    /** Main entry point */
    private suspend fun runLoop(): LoopResult {
        try {
            coroutineScope {
                launch { modelLoadingService() }
                launch { loggingService() }
                launch { rebuildAndReloadService() }
                launch(Dispatchers.IO) { orchestrationServer() }
                launch(Dispatchers.IO) { watchForChangesService() }

                runApplication()

                // When the app exits, need to cancel the entire scope
                cancel()
            }
        } catch (_: CancellationException) {
            // Exit gracefully
        } catch (_: RestartException) {
            return LoopResult.Restart
        }
        return LoopResult.Exit
    }

    private suspend fun CoroutineScope.runApplication() {
        // Wait for the first available valid model and launch the app
        val model = validStateFlow.first()

        logger.debug("Running the app")
        delegate.runApplication(state = model, orchestrationPort = async {
            @OptIn(DelicateHotReloadApi::class)
            orchestration.port.awaitOrThrow()
        }).getOrThrow()
    }

    private suspend fun loggingService() {
        HotReloadLogWriter.logFlow.collect {
            orchestration send createLogMessage(it.level, it.message, it.exception)
        }
    }

    context(scope: CoroutineScope)
    private suspend fun modelLoadingService() {
        /*
         First initial model load;
         We don't catch `UserReadableError` here, thus making errors on the first model read fatal.
         This is desirable as the project might not be hot-reloadable at all.

         The hot-reload loop only really kicks in after the first successful model load.
         */
        logger.debug("Initial reading model")
        stateFlow.value = InternalState(delegate.readModel().getOrThrow())

        val lastValidStateFlow = validStateFlow.stateIn(scope)

        // Subscribe to rebuild events
        // NOTE: We don't use `collectLatest` here as model reloads should be fast.
        rebuildEventsFlow.collect { rebuild ->
            stateFlow.update { old ->
                val newState = old?.state
                    // Drop the state when the model is invalidated
                    ?.takeUnless { rebuild.what == RebuildEvent.What.Everything }
                    ?: HotReloadLogWriter.captureLogsForHotReload {
                        logger.debug("Reloading model")
                        readModelAndCheckReloadability(
                            oldValidState = lastValidStateFlow.value,
                        )
                    }.getOrElse { e ->
                        orchestration send createBuildTaskResultFailure("MODEL RELOAD FAILED", e)
                        old?.recompileRequestId?.let {
                            orchestration send OrchestrationMessage.RecompileResult(it, exitCode = 1)
                        }
                        null
                    }

                newState?.let {
                    InternalState(
                        state = it,
                        recompileRequestId = rebuild.recompileRequestId,
                    )
                }
            }
        }
    }

    private suspend fun watchForChangesService() {
        data class PathsToWatch(
            val modelPaths: Set<Path>,
            val buildPaths: Set<Path>,
        )

        appOnlineMark.await()

        validStateFlow.map {
            PathsToWatch(
                modelPaths = it.model.modelDependencies(),
                buildPaths = it.hotAppDependencies.jvmComposeRunDependencies(),
            )
        }.onEach {
            logger.debug("Waiting for changes...")
        }.collectLatest { (modelPaths, buildPaths) ->
            coroutineScope {
                watchPaths(modelPaths + buildPaths).collect { (affectedPaths) ->
                    val rebuildTarget = if (affectedPaths.any(modelPaths::contains))
                        RebuildEvent.What.Everything else RebuildEvent.What.BuildOnly
                    rebuildEventsFlow.emit(RebuildEvent(what = rebuildTarget))
                }
            }
        }
    }

    private suspend fun orchestrationServer() {
        orchestration.use { server ->
            server.bind()
            server.start()
            logger.debug("Started server")

            server.asFlow().collect { message ->
                when (message) {
                    is OrchestrationMessage.ClientConnected -> {
                        if (!appOnlineMark.isCompleted && message.clientRole == OrchestrationClientRole.Application) {
                            appOnlineMark.complete(Unit)
                        }
                    }
                    is OrchestrationMessage.RestartRequest -> {
                        // We send the shutdown request to gracefully shut down the app during the loop cancellation.
                        server send OrchestrationMessage.ShutdownRequest("Restart requested")
                        /*
                         NOTE: Restart has to recreate the HotReloadLoop itself, including the orchestration server.
                         If we try to be smart about it and reuse the orchestration server, then the dev tools get
                         confused. So for now, restart the whole loop; it's not a big deal.
                        */
                        throw RestartException()
                    }
                    is OrchestrationMessage.RecompileRequest -> {
                        rebuildEventsFlow.emit(
                            RebuildEvent(
                                what = RebuildEvent.What.Everything,
                                recompileRequestId = message.messageId,
                            )
                        )
                    }
                }
            }
        }
    }

    private suspend fun rebuildAndReloadService() {
        appOnlineMark.await()

        logger.debug("Finished waiting for the app/server for reload loop")
        // Drop the initial state as it is not a rebuild; the initial state is handled by the app run itself.
        stateFlow.filterNotNull().drop(1).collectLatest { (state, recompileRequestId) ->
            // ^ `collectLatest` ^ is here because there can be new what made during the build.
            // In those cases we want to cancel the previous build and start anew.

            val changes = runBuild(recompileRequestId) {
                HotReloadLogWriter.captureLogsForHotReload {
                    delegate.rebuildClasses(state)
                }
            }

            if (changes != null) {
                val changesMap = buildMap {
                    changes.forEach { change ->
                        val changeType = when (change.type) {
                            IncrementalCache.Change.ChangeType.CREATED -> ChangeType.Added
                            IncrementalCache.Change.ChangeType.MODIFIED -> ChangeType.Modified
                            IncrementalCache.Change.ChangeType.DELETED -> ChangeType.Removed
                        }
                        put(change.path.toAbsolutePath().toFile(), changeType)
                    }
                }

                if (changesMap.isNotEmpty()) {
                    logger.debug("Sending classes reload request")
                    orchestration send OrchestrationMessage.ReloadClassesRequest(changesMap)
                } else {
                    logger.debug("No changes after build")
                }
            }
        }
    }

    private suspend fun runBuild(
        recompileRequestId: OrchestrationMessageId?,
        block: suspend () -> Result<ClasspathChanges>,
    ): ClasspathChanges? {
        logger.debug("Rebuild started")
        orchestration send OrchestrationMessage.BuildStarted()

        val changes = block().onSuccess {
            orchestration send createBuildTaskResultSuccess()
        }.onFailure { e ->
            orchestration send createBuildTaskResultFailure("BUILD FAILED", e)
        }.getOrNull()

        if (recompileRequestId != null) {
            orchestration send OrchestrationMessage.RecompileResult(
                recompileRequestId = recompileRequestId,
                exitCode = if (changes == null) 1 else 0,
            )
        }
        orchestration send OrchestrationMessage.BuildFinished()
        logger.debug("Rebuild finished")
        return changes
    }

    private suspend fun readModelAndCheckReloadability(
        oldValidState: State<*>,
    ): Result<State<Context>> {
        val result = delegate.readModel()
        if (result.isFailure) return result

        val oldVersion = getComposeHotReloadVersion(oldValidState.hotApp)
        val currentVersion = getComposeHotReloadVersion(result.getOrThrow().hotApp)

        // We can't reload the hot-reload agent itself without restarting.
        return if (oldVersion != currentVersion) {
            val e = Exception(
                "The hot reload version is changed from $oldVersion -> $currentVersion. " +
                        "This is not possible to reload live. " +
                        "Please try restarting the app or revert the changes to continue this session."
            )
            Result.failure(e)
        } else result
    }

    /**
     * May be issued:
     * - Explicitly by the dev-tools reload button (we receive via the [orchestrationServer])
     * - Via the [watchForChangesService]
     */
    private data class RebuildEvent(
        val what: What,
        /** If this event stems from the dev-tools recompile request */
        val recompileRequestId: OrchestrationMessageId? = null,
    ) {
        enum class What {
            /** Both model and build must be redone */
            Everything,
            /** Model is up to date, only rebuild */
            BuildOnly,
        }
    }

    private /*no data!*/ class InternalState<Context : HotReloadProjectContext>(
        val state: State<Context>,
        /** non-null if triggered by the dev-tools */
        val recompileRequestId: OrchestrationMessageId? = null,
    )

    private val logger = LoggerFactory.getLogger(javaClass)
}
