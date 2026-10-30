/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.nullableFlag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.unique
import io.ktor.client.plugins.*
import io.ktor.client.statement.*
import org.jetbrains.amper.cli.analytics.CliFusReporter
import org.jetbrains.amper.cli.context.ProjectCliContext
import org.jetbrains.amper.cli.getModuleByName
import org.jetbrains.amper.cli.options.moduleOption
import org.jetbrains.amper.cli.publish.ios.publishIosApp
import org.jetbrains.amper.cli.terminal.printCompletedMilestone
import org.jetbrains.amper.cli.terminal.promptBoolean
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.cli.withBackend
import org.jetbrains.amper.core.downloader.amperHttpClient
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.MAVEN_CENTRAL_REPOSITORY_ID
import org.jetbrains.amper.frontend.Model
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.aomBuilder.defaultMavenRepositories
import org.jetbrains.amper.frontend.dr.resolver.AmperResolutionSettings
import org.jetbrains.amper.frontend.dr.resolver.ModuleDependencies.Companion.moduleDependencies
import org.jetbrains.amper.frontend.publishingSettings
import org.jetbrains.amper.frontend.schema.ProductType
import org.jetbrains.amper.fus.schema.FusEvents
import org.jetbrains.amper.fus.schema.FusPublicationType
import org.jetbrains.amper.fus.schema.FusTransitiveOption
import org.jetbrains.amper.klibsio.KlibsIoNotifier
import org.jetbrains.amper.maven.publish.isMultiplatformPublication
import org.jetbrains.amper.maven.publish.publicationCoordinates
import org.jetbrains.amper.stdlib.collections.mapToSet
import org.jetbrains.amper.tasks.KOTLIN_TOOLCHAIN_ASC_KEY_ID_ENV
import org.jetbrains.amper.tasks.KOTLIN_TOOLCHAIN_ASC_KEY_ISSUER_ID_ENV
import org.jetbrains.amper.tasks.KOTLIN_TOOLCHAIN_ASC_KEY_PATH_ENV
import org.jetbrains.amper.tasks.getModuleDependencies
import kotlin.coroutines.cancellation.CancellationException

private const val KLIBS_API_URL_ENV = "KOTLIN_TOOLCHAIN_KLIBS_IO_API_URL"
private const val KLIBS_API_URL_DEFAULT = "https://api.klibs.io/notify/artifacts"

private const val IOS_TESTFLIGHT_REPO = "testflight"

internal class PublishCommand : AmperModelAwareCommand(name = "publish") {

    private val moduleNames by moduleOption(
        help = "The module to publish. If unspecified, the `publish` command publishes all modules with enabled " +
                "publication. This option can be repeated to publish multiple modules, except when publishing to " +
                "`$IOS_TESTFLIGHT_REPO`, where only one iOS app can be published at a time.",
    ).multiple().unique()

    private val repositoryId by argument(
        "repository-id",
        help = "The ID of the repository to publish to. For Maven publications, use an ID from the repositories list; " +
                "default Maven repositories have the following IDs: ${defaultMavenRepositories.map { "`${it.id}`" }}. " +
                "To publish an iOS app to TestFlight, use the special `$IOS_TESTFLIGHT_REPO` repository ID, " +
                "which does not need to be declared in the repositories list. Only one app can be published at a time."
    )

    private val transitive by option(
        "--transitive",
        help = "When enabled, the command also publishes modules that are local dependencies of the modules " +
                "specified with `-m`/`--module`. This option has no effect if `-m`/`--module` is not specified, " +
                "because all publishable modules are published anyway in that case. This option is ignored when " +
                "publishing to `$IOS_TESTFLIGHT_REPO`."
    ).nullableFlag("--non-transitive")

    private val klibsIoApiUrl: String
        get() {
            val overriddenKlibsIoApiUrl = System.getenv(KLIBS_API_URL_ENV)
                ?.takeIf { it.isNotBlank() }
            return overriddenKlibsIoApiUrl ?: KLIBS_API_URL_DEFAULT
        }

    private val klibsIoNotifier by lazy {
        KlibsIoNotifier(httpClient = amperHttpClient, apiUrl = klibsIoApiUrl)
    }

    override fun help(context: Context): String = """
        Publish library modules to a Maven repository, or an iOS app to TestFlight
        
        ### Maven publishing
        
        Publishes the modules with enabled publication to the Maven repository with the given ID, as specified in the
        repositories list. The default Maven repositories have the following IDs:
        ${defaultMavenRepositories.joinToString { "`${it.id}`" }}.
        
        Use `-m`/`--module` to publish only specific modules, and `--transitive` to also publish their local
        dependencies.
        
        ### iOS app publishing
        
        Publishes an `ios/app` module to App Store Connect and TestFlight with `kotlin publish $IOS_TESTFLIGHT_REPO`
        (`$IOS_TESTFLIGHT_REPO` is a special built-in repository ID)
        
        Only one app can be published at a time: if the project has several iOS app modules, select one with
        `-m`/`--module`. Requires macOS with Xcode and an App Store Connect API key. Only automatic signing in
        straightforward setups is supported; for more complex scenarios, use other tools, such as `fastlane`.
        
        The App Store Connect API key is configured with the following environment variables:
        
        * `$KOTLIN_TOOLCHAIN_ASC_KEY_PATH_ENV` - the path to the private key (`.p8` file)
        * `$KOTLIN_TOOLCHAIN_ASC_KEY_ID_ENV` - the key ID
        * `$KOTLIN_TOOLCHAIN_ASC_KEY_ISSUER_ID_ENV` - the issuer ID
        
        In an interactive terminal session, all of these values can be specified interactively instead: a wizard-like
        setup guides you through the necessary steps the first time you publish, and saves the entered values to the
        `app-store-connect-auth.properties` file in the module directory. When running non-interactively (e.g., on CI),
        the values are taken from this file if it exists, and from the environment variables otherwise (values from the
        file take precedence). The command exits with an error if anything is missing or wrong at any stage of the setup.
    """.trimIndent()

    override suspend fun run(cliContext: ProjectCliContext, model: Model) {
        CliFusReporter.log(FusEvents.PublishCommandInvoked) {
            type = if (repositoryId == IOS_TESTFLIGHT_REPO) FusPublicationType.Ios else FusPublicationType.Maven
            modulesFiltered = moduleNames.isNotEmpty()
            transitive = when (this@PublishCommand.transitive) {
                true -> FusTransitiveOption.Enabled
                false -> FusTransitiveOption.Disabled
                null -> FusTransitiveOption.Auto
            }
        }

        if (repositoryId == IOS_TESTFLIGHT_REPO) {
            if (transitive != null) {
                logger.warn("WARNING: `--transitive` is ignored for iOS publishing.")
            }
            publishIosApp(
                module = selectIosAppModule(model),
                cliContext = cliContext,
                model = model,
            )
            return
        }

        val modulesToPublish = selectModulesToPublish(model, cliContext).distinctBy { it.userReadableName }

        withBackend(cliContext, model) { backend ->
            backend.publish(
                modules = modulesToPublish,
                repositoryId = repositoryId,
            )
        }

        notifyKlibsIo(modulesToPublish)
        printSuccessfulCommandConclusion("Publication successful")
    }

    private suspend fun notifyKlibsIo(modules: List<AmperModule>) {
        if (repositoryId == MAVEN_CENTRAL_REPOSITORY_ID) {
            modules
                .filter { it.publishingSettings.mavenCentral.notifyKlibsIo }
                .filter { it.isMultiplatformPublication() }
                .forEach { klibsIoNotifier.notify(it) }
        }
    }

    /**
     * Sends a notification to klibs.io with KMP library coordinates for it to be later indexed on the website.
     */
    private suspend fun KlibsIoNotifier.notify(
        module: AmperModule,
    ) {
        val coordinates = module.publicationCoordinates(Platform.COMMON)

        logger.info("Notifying klibs.io about ${module.userReadableName}")
        try {
            val confirmation = notifyArtifactPublished(
                groupId = coordinates.groupId,
                artifactId = coordinates.artifactId,
                version = coordinates.version ?: error("Missing version of the publication coordinates."),
            )
            terminal.printCompletedMilestone("Successfully notified klibs.io about ${module.userReadableName}: ${confirmation.message}.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponseException) {
            logger.warn(
                "Failed to notify klibs.io about ${module.userReadableName}: HTTP ${e.response.status.value}\n${e.response.bodyAsText()}."
            )
        } catch (e: Exception) {
            logger.warn(
                "Failed to reach klibs.io at $klibsIoApiUrl about ${module.userReadableName}: $e."
            )
        }
    }

    private fun selectIosAppModule(model: Model): AmperModule {
        return when (moduleNames.size) {
            0 -> {
                val iosApps = model.modules.filter { it.type == ProductType.IOS_APP }
                when (iosApps.size) {
                    0 -> userReadableError("No `ios/app` modules in the project.")
                    1 -> iosApps.single()
                    else -> userReadableError(
                        "There is more than one `ios/app` module in the project. " +
                                "Please choose which one to publish using the `-m/--module` option."
                    )
                }
            }
            1 -> {
                val module = model.getModuleByName(moduleNames.single())
                if (module.type != ProductType.IOS_APP) {
                    userReadableError("The module `${module.userReadableName} is not an `ios/app` module.")
                }
                module
            }
            else -> userReadableError(
                "Only a single `ios/app` module can be published to the `$IOS_TESTFLIGHT_REPO` " +
                        "at one time. Please select a single module using the `-m/--module` option."
            )
        }
    }

    private fun selectModulesToPublish(model: Model, cliContext: ProjectCliContext): List<AmperModule> {
        if (moduleNames.isEmpty()) {
            return model.modules
        }
        val explicitModules = moduleNames.map { model.getModuleByName(it) }
        if (transitive == false) {
            return explicitModules
        }
        val transitiveModules = context(cliContext) { explicitModules.transitiveClosure() }
        if (transitive == true) {
            return transitiveModules
        }
        val extraModuleNames = transitiveModules.mapToSet { it.userReadableName } - moduleNames
        return if (extraModuleNames.isEmpty()) {
            explicitModules
        } else {
            val shouldPublishTransitives = askForTransitivityOrFail(extraModuleNames)
            if (shouldPublishTransitives) {
                transitiveModules
            } else {
                explicitModules
            }
        }
    }

    private fun askForTransitivityOrFail(extraModuleNames: Set<String>): Boolean {
        val message = "The selected modules have dependencies on ${extraModuleNames.size} other " +
                "local modules:\n${extraModuleNames.joinToString("\n") { " - $it" }}"
        if (!terminal.terminalInfo.interactive) {
            userReadableError {
                appendLine(message)
                appendLine(
                    "Please pass the --transitive or --non-transitive flag to decide whether they should be " +
                            "published as well."
                )
            }
        }
        terminal.println(message)
        val response = terminal.promptBoolean("Do you want to publish these modules as well?", default = true)
        return response ?: throw PrintMessage("No option selected, publication canceled")
    }
}

context(cliContext: ProjectCliContext)
private fun Collection<AmperModule>.transitiveClosure(): List<AmperModule> =
    asSequence()
        .flatMap { it.transitiveClosure() }
        .distinctBy { it.userReadableName }
        .toList()

context(cliContext: ProjectCliContext)
private fun AmperModule.transitiveClosure(): Sequence<AmperModule> = sequence {
    yield(this@transitiveClosure)
    val moduleDependencies = with(cliContext) { moduleDependencies(AmperResolutionSettings(userCacheRoot, incrementalCache, openTelemetry)) }
    leafPlatforms.forEach { platform ->
        yieldAll(moduleDependencies.getModuleDependencies(isTest = false, platform = platform, dependencyReason = ResolutionScope.COMPILE))
        yieldAll(moduleDependencies.getModuleDependencies(isTest = false, platform = platform, dependencyReason = ResolutionScope.RUNTIME))
    }
}.distinctBy { it.userReadableName }
