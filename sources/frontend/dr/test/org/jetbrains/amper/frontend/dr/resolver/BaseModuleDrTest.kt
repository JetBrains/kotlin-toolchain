/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.dr.resolver

import io.opentelemetry.api.OpenTelemetry
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import org.intellij.lang.annotations.Language
import org.jetbrains.amper.buildinfo.AmperBuild
import org.jetbrains.amper.dependency.resolution.Context
import org.jetbrains.amper.dependency.resolution.DependencyNode
import org.jetbrains.amper.dependency.resolution.FileCacheBuilder
import org.jetbrains.amper.dependency.resolution.IncrementalCacheUsage
import org.jetbrains.amper.dependency.resolution.MavenCoordinates
import org.jetbrains.amper.dependency.resolution.MavenDependencyNode
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.dependency.resolution.ResolvedGraph
import org.jetbrains.amper.dependency.resolution.RootDependencyNodeStub
import org.jetbrains.amper.dependency.resolution.diagnostics.Message
import org.jetbrains.amper.dependency.resolution.diagnostics.Severity
import org.jetbrains.amper.dependency.resolution.diagnostics.SimpleDiagnosticDescriptor
import org.jetbrains.amper.dependency.resolution.diagnostics.detailedMessage
import org.jetbrains.amper.dependency.resolution.getDefaultFileCacheBuilder
import org.jetbrains.amper.dependency.resolution.unwrap
import org.jetbrains.amper.dependency.resolution.withFilteredChildren
import org.jetbrains.amper.frontend.Model
import org.jetbrains.amper.frontend.dr.resolver.BaseModuleDrTest.Companion.DelayedAssertion.Companion.withDelayedSoftAssertion
import org.jetbrains.amper.frontend.schema.DefaultVersions
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.test.Dirs
import org.jetbrains.amper.test.assertEqualsWithDiff
import org.jetbrains.amper.test.golden.goldenFileOsArchAware
import org.jetbrains.amper.test.runTestRespectingDelays
import org.junit.jupiter.api.TestInfo
import org.opentest4j.AssertionFailedError
import java.nio.file.Path
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.createFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeLines
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

abstract class BaseModuleDrTest {
    protected open val testGoldenFilesRoot: Path = Dirs.amperSourcesRoot.resolve("frontend/dr/testData/goldenFiles")
    protected val testDataRoot: Path = Dirs.amperSourcesRoot.resolve("frontend/dr/testData/projects")

    private val defaultMessagesCheck: (DependencyNode) -> Unit = { node ->
        val messages = node.messages.defaultFilterMessages()
        assertTrue(messages.isEmpty(), "There must be no messages for $this:\n${messages.joinToString("\n") { it.detailedMessage }}")
    }

    protected suspend fun doTestByFile(
        testInfo: TestInfo,
        aom: Model,
        resolutionInput: TestResolutionInput = defaultTestResolutionInput,
        verifyMessages: Boolean = true,
        module: String? = null,
        fragment: String? = null,
        goldenFileName: String = testInfo.testMethod.get().name,
        filter: ModuleResolutionFilter = ModuleResolutionFilter(),
        messagesCheck: (DependencyNode) -> Unit = defaultMessagesCheck
    ): DependencyNode {
        val goldenFile = goldenFileOsArchAware(
            "${goldenFileName.replace(" ", "_")}.tree.txt")
        val expected = getGoldenFileText(goldenFile, fileDescription = "Golden file for resolved tree")
        return doTest(
            aom, resolutionInput, verifyMessages, expected, module, fragment, filter,
            expectedCheckWrapper = { block ->
                withActualDumpAndDelayedAssertion(goldenFile) { block() }
            },
            messagesCheck
        )
    }

    protected suspend fun doTest(
        aom: Model,
        resolutionInput: TestResolutionInput = defaultTestResolutionInput,
        verifyMessages: Boolean = true,
        @Language("text") expected: String? = null,
        module: String? = null,
        fragment: String? = null,
        filter: ModuleResolutionFilter = ModuleResolutionFilter(),
        expectedCheckWrapper: suspend (block:() -> Unit) -> Unit = { it() },
        messagesCheck: (DependencyNode) -> Unit = defaultMessagesCheck
    ): DependencyNode {
        val resolutionSettings = resolutionInput.resolutionSettings
        val resolutionRunSettings = resolutionInput.resolutionRunSettings
            .copy(incrementalCacheUsage = getIncrementalCacheUsage())

        val graph =
            if (module == null
                && ideSyncTestResolutionInput.resolutionSettings.includeNonExportedNative == resolutionSettings.includeNonExportedNative
                && filter.resolutionType == ResolutionType.ALL)
            {
                with (ModuleDependencies) {
                    aom.resolveProjectDependencies(
                        resolutionSettings,
                        resolutionRunSettings,
                    ).also { checkMessages(verifyMessages, it, messagesCheck) }
                        .root
                        .let {
                            if (filter.scope != null) {
                                it.withFilteredChildren { child, _ ->
                                    child !is DirectFragmentDependencyNode
                                            || child.dependencyNode.dependency.resolutionConfig.scope == filter.scope
                                }
                            } else it
                        }
                }
            } else {
                val modules = aom.modules.filter { module == null || it.userReadableName == module }
                ModuleDependencies.resolveModuleDependencies(
                    modules,
                    resolutionSettings,
                    resolutionRunSettings,
                    filter = filter,
                )
                    .also { checkMessages(verifyMessages, it, messagesCheck) }
                    .let {
                        if (module != null && fragment != null) {
                            if (filter.platforms != null) error("platforms could not be used as a filter together with fragment")
                            val fragmentDeps = it.root.children
                                .filterIsInstance<ModuleDependencyNode>()
                                .fragmentDependencies(module, fragment, aom)
                            RootDependencyNodeStub(
                                graphEntryName = "Fragment '$module:$fragment' dependencies",
                                children = fragmentDeps
                            )
                        } else {
                            it.root
                        }
                    }
            }


        expected?.let {
            expectedCheckWrapper {
                assertModuleDepsEquals(expected, graph)
            }
        }

        return graph
    }

    protected fun cacheBuilder(cacheRoot: Path): FileCacheBuilder.() -> Unit = {
        getDefaultFileCacheBuilder(cacheRoot).invoke(this)
        readOnlyExternalRepositories = emptyList()
    }

    protected fun assertModuleDepsEquals(@Language("text") expected: String, graph: DependencyNode, forMavenNode: MavenCoordinates? = null) {
        val moduleDeps = graph.children.filter { it.unwrap() is ModuleDependencyNode }
        assertEquals(moduleDeps.size, graph.children.size,
            "Unexpected dependency type is among root children: " +
                    (graph.children - moduleDeps.toSet()).joinToString { it::class.java.simpleName }
        )
        assertEquals(expected, graph.children, forMavenNode)
    }

    private fun assertEquals(@Language("text") expected: String, roots: List<DependencyNode>, forMavenNode: MavenCoordinates? = null) {
        val actual = StringBuilder()
        roots.forEach {
            actual.append(it.prettyPrint(forMavenNode))
        }
        assertEqualsWithDiff(
            expected.trimEnd().lines(),
            actual.trimEnd().lines()
        )
    }

    protected suspend fun assertFiles(
        testInfo: TestInfo,
        root: DependencyNode,
        withSources: Boolean = false,
        scope: ResolutionScope? = null,
    ) {
        val goldenFile = goldenFileOsArchAware(
            "${testInfo.testMethod.get().name.replace(" ", "_")}.files.txt")
        val expected = getGoldenFileText(goldenFile, fileDescription = "Golden file for files")
        withActualDumpAndDelayedAssertion(goldenFile) {
            assertFiles(expected.trim().lines(), root, withSources, scope)
        }
    }

    // todo (AB) : Reuse utility methods from dependence-resolution test module
    protected fun assertFiles(
        expectedFiles: List<String>,
        root: DependencyNode,
        withSources: Boolean = false,
        scope: ResolutionScope? = null,
    ) {
        root.distinctBfsSequence()
            .filter{ it.unwrap() is MavenDependencyNode }
            .groupBy { (it.unwrap() as MavenDependencyNode).dependency.resolutionConfig.scope } // todo (AB) : Group by module and test/main as well
            .filterKeys { scope == null || it == scope }
            .mapValues {
                it.value
                    .flatMap {
                        val mavenDependency = (it.unwrap() as MavenDependencyNode)
                        mavenDependency.dependency.files(withSources) + mavenDependency.dependency.kmpResourcesFiles
                    }
                    .mapNotNull { it.path?.takeIf { it.exists() } }
                    .sortedBy { it.name }
                    .toSet()
            }.also { filesPerScope ->
                val actualList = buildList {
                    for (key in filesPerScope.keys.sorted()) {
                        add("$key")
                        addAll(filesPerScope[key]!!.map { it.name })
                    }
                }
                assertEqualsWithDiff(expectedFiles, actualList)
            }
    }

    protected fun getGoldenFileText(goldenFile: Path, fileDescription: String): String {
        if (!goldenFile.exists()) { goldenFile.createFile() }
        return goldenFile
            .readText()
            .replace("#kotlinVersion", DefaultVersions.kotlin)
            .replace("#composeDefaultVersion", DefaultVersions.compose)
            .trim()
    }

    protected fun goldenFileOsArchAware(goldenFileBaseName: String) =
        testGoldenFilesRoot.goldenFileOsArchAware(goldenFileBaseName)

    companion object {
        /**
         * Run every test twice if [checkIncrementalCache] is set to true
         * (the first run without cache, the second with cache populated during the first run)
         */
        internal fun runModuleDependenciesTest(
            checkIncrementalCache: Boolean = true,
            timeout: Duration = 1.minutes,
            testBody: suspend TestScope.() -> Unit
        ) {
            val testBodyWithDelayedSoftAssertions: (suspend TestScope.() -> Unit) =
                {
                    withDelayedSoftAssertion {
                        testBody()
                    }
                }

            if (checkIncrementalCache) {
                val incrementalCacheUsageContext =
                    IncrementalCacheUsageContextElement(IncrementalCacheUsage.REFRESH_AND_USE)
                runTestRespectingDelays(
                    context = incrementalCacheUsageContext,
                    timeout = timeout,
                    testBody = {
                        executeWithAndWithoutCache(incrementalCacheUsageContext, testBodyWithDelayedSoftAssertions)
                    }
                )
            } else {
                runTestRespectingDelays(testBody = testBodyWithDelayedSoftAssertions, timeout = timeout)
            }
        }

        /**
         * Run every test twice if [checkIncrementalCache] is set to true
         * (the first run without cache, the second with cache populated during the first run)
         *
         * Test timeout is 5 minutes by default
         */
        internal fun runSlowModuleDependenciesTest(
            checkIncrementalCache: Boolean = true,
            timeout: Duration = 5.minutes,
            testBody: suspend TestScope.() -> Unit
        ) = runModuleDependenciesTest(checkIncrementalCache, timeout, testBody)

        private suspend fun TestScope.executeWithAndWithoutCache(
            incrementalCacheUsageContext: IncrementalCacheUsageContextElement,
            testBody: suspend TestScope.() -> Unit,
        ) {
            try {
                println("Running test with resolutionCacheUsage=${incrementalCacheUsageContext.incrementalCacheUsage}")
                testBody()

                incrementalCacheUsageContext.incrementalCacheUsage = IncrementalCacheUsage.SKIP
                println("Running test with resolutionCacheUsage=${incrementalCacheUsageContext.incrementalCacheUsage}")
                testBody()
            } finally {
                incrementalCacheUsageContext.incrementalCacheUsage = IncrementalCacheUsage.SKIP
            }
        }

        internal suspend fun getIncrementalCacheUsage() =
            currentCoroutineContext()[IncrementalCacheUsageContextElementKey]?.incrementalCacheUsage?: IncrementalCacheUsage.SKIP

        fun List<Message>.defaultFilterMessages(): List<Message> =
            filter { "Downloaded " !in it.message && "Resolved " !in it.message }

        internal fun DependencyNode.verifyOwnMessages(filterMessages: List<Message>.() -> List<Message> = { defaultFilterMessages() }) {
            val messages = this.messages.filterMessages()
            assertTrue(
                messages.isEmpty(),
                "There must be no messages for $this:\n${messages.joinToString("\n") { it.detailedMessage }}"
            )
        }

        private inline fun <T> withActualDump(expectedResultPath: Path? = null, block: () -> T): T {
            contract {
                callsInPlace(block, InvocationKind.EXACTLY_ONCE)
                returnsResultOf(block)
            }
            return try {
                block()
            } catch (e: AssertionFailedError) {
                if (e.isExpectedDefined && e.isActualDefined && expectedResultPath != null) {
                    val actualResultPath = expectedResultPath.parent.resolve(expectedResultPath.fileName.name + ".tmp")
                    when(val actualValue = e.actual.value) {
                        is List<*> -> actualResultPath.writeLines(actualValue.map { it.toString().replaceVersionsWithVariables() })
                        is String -> actualResultPath.writeText(actualValue.replaceVersionsWithVariables())
                        else -> { /* do nothing */ }
                    }
                }
                throw SoftAssertionFailedError(e)
            }.also {
                expectedResultPath?.parent?.resolve(expectedResultPath.fileName.name + ".tmp")?.deleteIfExists()
            }
        }

        internal suspend fun withActualDumpAndDelayedAssertion(expectedResultPath: Path? = null, block: () -> Unit) =
            withDelayedSoftAssertion {
                withActualDump(expectedResultPath, block)
            }

        internal class SoftAssertionFailedError(e: AssertionFailedError): AssertionFailedError(e.message, e) {
            override val cause: Throwable get() = super.cause!!
        }

        internal class DelayedAssertion {
            private val assertions: MutableList<SoftAssertionFailedError> = mutableListOf()

            fun add(e: SoftAssertionFailedError) { assertions.add(e) }

            /**
             * This method raises already registered assertions if any,
             * or re-throw the given [exception] if it is not null.
             *
             * Details:
             * - If there are registered assertions, the first one is thrown,
             *   with all other attached as suppressed exceptions. Given [exception] is also added as a suppressed one in this case.
             * - If there is no assertion registered, and non-null [exception] is passed, then it is re-thrown.
             * - If neither assertions are registered nor the [exception] is passed, then the method does nothing and simply returns.
             */
            private fun assert(exception: Exception? = null) {
                when (assertions.size) {
                    0 -> if (exception != null) throw exception
                    1 -> throw assertions[0].cause.withSuppressed(exception)
                    else -> throw assertions.subList(1, assertions.size).fold(assertions[0].cause) { acc, e -> acc.also { acc.addSuppressed(e.cause) } }.withSuppressed(exception)
                }
            }

            private fun Throwable.withSuppressed(t: Throwable?) =
                this.also { if (t != null) this.addSuppressed(t) }

            companion object {
                private suspend fun getCurrentDelayedAssertionElement(): DelayedAssertionContextElement? =
                    currentCoroutineContext()[DelayedAssertionKey]

                private object DelayedAssertionKey: CoroutineContext.Key<DelayedAssertionContextElement>

                private class DelayedAssertionContextElement(
                    var delayedAssertion: DelayedAssertion
                ) : CoroutineContext.Key<DelayedAssertionContextElement>, CoroutineContext.Element {
                    override val key: CoroutineContext.Key<*> get() = DelayedAssertionKey
                    override fun toString(): String = "DelayedAssertionContextElement"
                }

                /**
                 * Run given [block].
                 * [SoftAssertionFailedError] occurred inside the block is either caught there and registered by nested
                 * [SoftAssertionFailedError] or thrown directly from the [block] and is registered by this method.
                 *
                 * Registered [SoftAssertionFailedError]s are immediately thrown at the end of this method (theur actual causes to be precise)
                 * if either
                 * - [Exception] occurred inside the [block] (i.e., something is wrong and there is no need to proceed with accumulating assertions)
                 * - or if the block finished successfully, and delayed assertion context didn't exist before this method started executing.
                 *
                 * Note: If several [SoftAssertionFailedError]s were caught, the first one's cause is thrown with all the rest being added as suppressed ones.
                 *
                 * If block finished successfully, and delayed assertion context was set up before entering this method,
                 * then registered [SoftAssertionFailedError] are not thrown.
                 * It is the responsibility of a caller to handle them in that case.
                 *
                 * This way top-most call of [withDelayedSoftAssertion] raise all accumulated assertions.
                 */
                internal suspend fun withDelayedSoftAssertion(block: suspend () -> Unit) {
                    val upstreamDelayedAssertion = getCurrentDelayedAssertionElement()
                    val delayedAssertionContextElement = upstreamDelayedAssertion ?: DelayedAssertionContextElement(DelayedAssertion())
                    val delayedAssertion = delayedAssertionContextElement.delayedAssertion

                    try {
                        withContext(delayedAssertionContextElement) {
                            block()
                        }
                    } catch (e: SoftAssertionFailedError) {
                        delayedAssertion.add(e)
                    } catch (t: Exception) {
                        // exception occurred, we stop collecting assertions at this point and raise what we already have.
                        delayedAssertion.assert(t)
                    }

                    if (upstreamDelayedAssertion == null) {
                        // raise assertions registered inside the given block.
                        // (delayed assertion context didn't exist before entering this method)
                        delayedAssertion.assert()
                    }
                }
            }
        }

        private fun String.replaceVersionsWithVariables(): String =
            replaceArtifactFilenames(
                filePrefix = "kotlin-stdlib",
                version = DefaultVersions.kotlin,
                versionVariableName = "kotlinVersion",
            )
                .replaceCoordinateVersionWithReference(
                    groupPrefix = "org.jetbrains.kotlin",
                    artifactPrefix = "kotlin-",
                    version = DefaultVersions.kotlin,
                    versionVariableName = "kotlinVersion",
                )
                .replaceCoordinateVersionWithReference(
                    groupPrefix = "org.jetbrains.compose",
                    artifactPrefix = "",
                    version = DefaultVersions.compose,
                    versionVariableName = "composeDefaultVersion",
                )

        private fun String.replaceArtifactFilenames(
            filePrefix: String,
            version: String,
            versionVariableName: String,
        ): String = replace(Regex("""${Regex.escape(filePrefix)}.*-${Regex.escape(version)}\.(jar|aar|klib)""")) {
            it.value.replace(version, "#$versionVariableName")
        }
        private fun String.replaceCoordinateVersionWithReference(
            groupPrefix: String,
            artifactPrefix: String,
            version: String,
            versionVariableName: String,
        ): String = replace(Regex("""${Regex.escape(groupPrefix)}[^:]*:${Regex.escape(artifactPrefix)}[^:]*:([\w.\-]+ -> )?${Regex.escape(version)}""")) {
            it.value.replace(version, "#$versionVariableName")
        }
    }

    internal fun assertTheOnlyNonInfoMessage(
        root: DependencyNode,
        diagnostic: SimpleDiagnosticDescriptor,
        severity: Severity = diagnostic.defaultSeverity,
        transitively: Boolean = false
    ) {
        val nodes = if (transitively) root.distinctBfsSequence() else sequenceOf(root)
        val messages = nodes.flatMap{ it.children.flatMap { it.messages.defaultFilterMessages() } }
        val message = messages.singleOrNull()
        assertNotNull(message, "A single error message is expected, but found: ${messages.toSet()}")
        assertEquals(
            diagnostic.id,
            message.id,
            "Unexpected error message"
        )
        assertEquals(
            severity,
            message.severity,
            "Unexpected severity of the error message"
        )
    }

    // todo (AB): [AMPER-4905] Remove on final cleanup
    protected suspend fun <T> timed(text: String, block: suspend () -> T): T {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
            returnsResultOf(block)
        }
        val start = System.currentTimeMillis()
        return try {
            block()
        } finally {
            val end = System.currentTimeMillis()
            println("#######################")
            println("Execution time: ${end - start} ms ($text)")
        }
    }

    // todo (AB): [AMPER-4905] Remove on final cleanup
    protected fun <T> timedBlocking(text: String, block: () -> T): T {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
            returnsResultOf(block)
        }
        val start = System.currentTimeMillis()
        return try {
            block()
        } finally {
            val end = System.currentTimeMillis()
            println("#######################")
            println("Execution time: ${end - start} ms ($text)")
        }
    }

    protected fun context(
        scope: ResolutionScope = ResolutionScope.COMPILE,
        platform: Set<ResolutionPlatform> = setOf(ResolutionPlatform.JVM),
        cacheBuilder: FileCacheBuilder.() -> Unit = cacheBuilder(Dirs.userCacheRoot),
        openTelemetry: OpenTelemetry? = null,
        incrementalCache: IncrementalCache? = null
    ) = Context {
        this.scope = scope
        this.platforms = platform
        this.cache = cacheBuilder
        this.openTelemetry = openTelemetry
        this.incrementalCache = incrementalCache
    }
}

private fun checkMessages(
    verifyMessages: Boolean,
    graph: ResolvedGraph,
    messagesCheck: (DependencyNode) -> Unit,
) {
    if (verifyMessages) {
        graph.root.distinctBfsSequence().forEach {
            messagesCheck(it)
        }
    }
}

private object IncrementalCacheUsageContextElementKey: CoroutineContext.Key<IncrementalCacheUsageContextElement>

internal class IncrementalCacheUsageContextElement(
    var incrementalCacheUsage: IncrementalCacheUsage
) : CoroutineContext.Key<IncrementalCacheUsageContextElement>, CoroutineContext.Element {
    override val key: CoroutineContext.Key<*>
        get() = IncrementalCacheUsageContextElementKey

    override fun toString(): String = "ResolutionCacheUsageContextElement"
}

data class TestResolutionInput(
    val resolutionSettings: AmperResolutionSettings = defaultTestResolutionSettings,
    val resolutionRunSettings: ResolutionRunSettings = defaultResolutionRunSettings,
)

internal val defaultTestResolutionSettings = AmperResolutionSettings(
    amperUserCacheRoot,
    incrementalCache = IncrementalCache(
        stateRoot = amperUserCacheRoot.path.resolve("incremental.state"),
        codeVersion = AmperBuild.commitHash
    ),
)

internal val defaultTestResolutionInput = TestResolutionInput()
internal val ideSyncTestResolutionInput = defaultTestResolutionInput
    .copy(
        resolutionSettings = defaultTestResolutionInput.resolutionSettings.copy(includeNonExportedNative = false)
    )
internal val ideSyncModuleResolutionFilter = ModuleResolutionFilter(resolutionType = ResolutionType.ALL)