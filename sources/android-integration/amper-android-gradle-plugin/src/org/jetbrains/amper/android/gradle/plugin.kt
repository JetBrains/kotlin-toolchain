/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */
package org.jetbrains.amper.android.gradle

import com.android.build.api.component.analytics.AnalyticsEnabledComponent
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.Component
import com.android.build.gradle.internal.component.ComponentCreationConfig
import kotlinx.serialization.json.Json
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.initialization.Settings
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.ExtraPropertiesExtension
import org.gradle.api.problems.Problems
import org.gradle.api.problems.Severity
import org.gradle.api.provider.Property
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry
import org.jetbrains.amper.android.AndroidBuildRequest
import org.jetbrains.amper.android.SYNTHETIC_ROOT_ANDROID_PROJECT_PATH
import org.jetbrains.amper.android.gradle.tooling.MockableJarModelBuilder
import org.jetbrains.amper.android.gradle.tooling.ProcessResourcesProviderTaskNameToolingModelBuilder
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.LeafFragment
import org.jetbrains.amper.frontend.LocalModuleDependency
import org.jetbrains.amper.frontend.Model
import org.jetbrains.amper.frontend.Platform
import org.jetbrains.amper.frontend.aomBuilder.readProjectModel
import org.jetbrains.amper.frontend.project.AmperProjectContext
import org.jetbrains.amper.frontend.schema.ProductType
import org.jetbrains.amper.frontend.schema.keyAlias
import org.jetbrains.amper.frontend.schema.keyPassword
import org.jetbrains.amper.frontend.schema.storeFile
import org.jetbrains.amper.frontend.schema.storePassword
import org.jetbrains.amper.frontend.singleSourceRoot
import org.jetbrains.amper.mavencentral.MavenCentralDefaultConfiguration
import org.jetbrains.amper.problems.reporting.NoopProblemReporter
import org.jetbrains.amper.stdlib.properties.readProperties
import java.io.File
import java.nio.file.Path
import javax.inject.Inject
import javax.xml.stream.XMLEventFactory
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLOutputFactory
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isSameFileAs
import kotlin.io.path.pathString
import kotlin.io.path.relativeTo

interface AmperAndroidIntegrationExtension {
    val jsonData: Property<String>
}

private const val PROJECT_TO_MODULE_EXT = "org.jetbrains.amper.gradle.android.ext.projectToModule"
private const val MODULE_TO_PROJECT_EXT = "org.jetbrains.amper.gradle.android.ext.moduleToProject"
private const val ANDROID_REQUEST = "org.jetbrains.amper.gradle.android.ext.androidRequest"
private const val KNOWN_MODEL_EXT = "org.jetbrains.amper.gradle.android.ext.model"
private const val MOCKABLE_JAR_ARTIFACTS_EXT = "org.jetbrains.amper.gradle.android.ext.mockableJarArtifacts"

private fun <K, V> ExtraPropertiesExtension.getBindingMap(name: String): MutableMap<K, V> = try {
    @Suppress("UNCHECKED_CAST") // it's ok because we always read and write the map from the same typed place below
    this[name] as MutableMap<K, V>
} catch (_: ExtraPropertiesExtension.UnknownPropertyException) {
    val bindingMap = mutableMapOf<K, V>()
    this[name] = bindingMap
    bindingMap
}

val Gradle.projectPathToModule: MutableMap<String, AmperModule>
    get() = extensions.extraProperties.getBindingMap(PROJECT_TO_MODULE_EXT)

val Gradle.moduleFilePathToProject: MutableMap<Path, String>
    get() = extensions.extraProperties.getBindingMap(MODULE_TO_PROJECT_EXT)

var Gradle.request: AndroidBuildRequest?
    get() = extensions.extraProperties[ANDROID_REQUEST]?.let { it as AndroidBuildRequest }
    set(value) {
        extensions.extraProperties[ANDROID_REQUEST] = value
    }

var Gradle.knownModel: Model?
    get() = extensions.extraProperties[KNOWN_MODEL_EXT]?.let { it as Model }
    set(value) {
        extensions.extraProperties[KNOWN_MODEL_EXT] = value
    }

/**
 * The mockable `android.jar` artifact captured per Gradle project path (see the project plugin's `onVariants`
 * handler). It is resolved lazily by [org.jetbrains.amper.android.gradle.tooling.MockableJarModelBuilder] during the
 * Test-phase model query, where the `androidApis` configuration that normally exposes it doesn't exist yet.
 */
val Gradle.mockableJarArtifacts: MutableMap<String, FileCollection>
    get() = extensions.extraProperties.getBindingMap(MOCKABLE_JAR_ARTIFACTS_EXT)


val AmperModule.buildFile get() = source.buildFile

val AmperModule.buildDir: Path get() = buildFile.parent

private const val SIGNING_CONFIG_NAME = "sign"

/**
 * AGP's `onVariants` callback delivers either the real variant (which implements [ComponentCreationConfig]) or, when
 * Gradle build profiling is enabled, an [AnalyticsEnabledComponent] wrapper around it. Unwrap to reach AGP's internal
 * creation config, from which the global mockable `android.jar` artifact is available.
 */
private val Component.creationConfig: ComponentCreationConfig?
    get() = ((this as? AnalyticsEnabledComponent)?.delegate ?: this) as? ComponentCreationConfig

@Suppress("UnstableApiUsage")
class AmperAndroidIntegrationProjectPlugin @Inject constructor(private val problems: Problems) : Plugin<Project> {
    override fun apply(project: Project) {
        val rootProjectBuildDir = project.rootProject.layout.buildDirectory.asFile.get().toPath()
        val buildDir = rootProjectBuildDir / project.path.replace(":", "_")
        project.layout.buildDirectory.set(buildDir.toFile())
        project.repositories.google()
        if (!MavenCentralDefaultConfiguration.isDirectUrl) {
            project.repositories.maven { repo ->
                repo.name = "Maven Central (proxy)"
                repo.setUrl(MavenCentralDefaultConfiguration.url)
            }
        } else {
            project.repositories.mavenCentral()
        }

        val module = project.gradle.projectPathToModule[project.path] ?: return

        project.plugins.apply("com.android.application")

        if ((module.buildDir / "google-services.json").exists()) {
            project.plugins.apply("com.google.gms.google-services")
        }

        val androidExtension = project.extensions.findByType(ApplicationExtension::class.java) ?: return
        project.setArtifactBaseName()

        val androidFragment = module
            .fragments
            .filterIsInstance<LeafFragment>()
            .firstOrNull { it.platforms.contains(Platform.ANDROID) } ?: return

        val androidSettings = androidFragment.settings.android
        androidExtension.compileSdk {
            version = release(androidSettings.compileSdk.apiLevel.versionNumber) {
                minorApiLevel = androidSettings.compileSdk.minorApiLevel
                sdkExtension = androidSettings.compileSdk.sdkExtension
            }
        }

        val signing = androidSettings.signing

        if (signing.enabled) {
            val path = (module.buildDir / signing.propertiesFile.pathString).normalize().absolute()
            if (path.exists()) {
                val keystoreProperties = path.readProperties()
                val signingConfig = androidExtension.signingConfigs.create(SIGNING_CONFIG_NAME)
                keystoreProperties.storeFile?.let { storeFile ->
                    signingConfig.storeFile = (module.buildDir / Path(storeFile)).toFile()
                }
                keystoreProperties.storePassword?.let { storePassword ->
                    signingConfig.storePassword = storePassword
                }
                keystoreProperties.keyAlias?.let { keyAlias ->
                    signingConfig.keyAlias = keyAlias
                }
                keystoreProperties.keyPassword?.let { keyPassword ->
                    signingConfig.keyPassword = keyPassword
                }
            } else {
                // FIXME All APIs from org.gradle.api.problems.ProblemReporter that are present in our 8.11 library were
                //   actually removed in Gradle 9, and lead to NoSuchMethodError (see KTC-5662).
                //   As long as we don't use the correct Gradle API library, we can't use this.
//                problems.reporter.reporting { problem ->
//                    problem
//                        .id("signing-properties-file-not-found", "Signing properties file not found")
//                        .contextualLabel("Signing properties file not found")
//                        .details("Signing properties file $path not found. Signing will not be configured")
//                        .severity(Severity.WARNING)
//                        .solution("Put signing properties file to $path")
//                }
                project.logger.warn("Signing properties file $path not found. Signing will not be configured")
            }
        }

        androidExtension.defaultConfig {
            targetSdk = androidSettings.targetSdk.versionNumber
            minSdk = androidSettings.minSdk.versionNumber
            versionCode = androidSettings.versionCode
            versionName = androidSettings.versionName
            if (module.type == ProductType.ANDROID_APP) {
                applicationId = androidSettings.applicationId
            }
        }
        androidExtension.namespace = androidSettings.namespace

        androidExtension.packaging.resources {
            val resourcePackaging = androidSettings.resourcePackaging
            excludes.addAll(resourcePackaging.excludes.map { it.value })
            merges.addAll(resourcePackaging.merges.map { it.value })
            pickFirsts.addAll(resourcePackaging.pickFirsts.map { it.value })
        }

        val release = androidExtension.buildTypes.getByName("release")
        val proguardRulesFile = (module.buildDir / "proguard-rules.pro").toFile()
        // AGP 9's R8 fails if a supplied proguard configuration file doesn't exist (AGP 8 silently ignored it),
        // so we only pass the module's proguard-rules.pro when it's actually present.
        val proguardFiles = buildList<Any> {
            add(androidExtension.getDefaultProguardFile("proguard-android-optimize.txt"))
            if (proguardRulesFile.exists()) add(proguardRulesFile)
        }
        release.proguardFiles(*proguardFiles.toTypedArray())
        release.isDebuggable = false
        release.isMinifyEnabled = true
        release.isShrinkResources = true
        androidExtension.signingConfigs.findByName(SIGNING_CONFIG_NAME)?.let { signingConfig ->
            release.signingConfig = signingConfig
        }

        val requestedModules = project
            .gradle
            .request
            ?.modules
            ?.associate { it.modulePath to it } ?: mapOf()

        androidExtension.sourceSets.matching { it.name == "main" }.all {
            val androidApplicationSourceRoot = androidFragment
                .singleSourceRoot("Android application must have a single source root")
                .resolve("AndroidManifest.xml")
            it.manifest.srcFile(androidApplicationSourceRoot)
            it.assets.setSrcDirs(setOf(module.buildDir.resolve("assets")))
            it.res.setSrcDirs(setOf(module.buildDir.resolve("res")))
            it.jniLibs.setSrcDirs(setOf(module.buildDir.resolve("jniLibs")))
        }

        val androidComponents =
            project.extensions.findByType(ApplicationAndroidComponentsExtension::class.java) ?: return
        val buildTypes = (project.gradle.request?.buildTypes ?: emptySet()).map { it.value }.toSet()

        androidComponents.onVariants(androidComponents.selector().all()) { variant ->
            // Capture AGP's own mockable android.jar so the Test-phase MockableJarModel query can resolve it.
            // Under AGP 9 the `androidApis` configuration that exposes the mockable jar isn't created during a plain
            // tooling-model query (it's created later, during task-graph configuration). Accessing AGP's
            // GlobalTaskCreationConfig.mockableJarArtifact here creates and wires that configuration during the
            // configuration phase, so MockableJarModelBuilder can resolve it. This is the same artifact AGP's own
            // v2 model builder returns via VariantModel.mockableJarArtifact.
            variant.creationConfig?.let { creationConfig ->
                project.gradle.mockableJarArtifacts[project.path] = creationConfig.global.mockableJarArtifact
            }

            if (variant.buildType !in buildTypes) return@onVariants
            val requestedModule = requestedModules[project.path] ?: return@onVariants

            // AGP 9 added built-in Kotlin support, which contributes its own kotlin-stdlib (a Maven coordinate) to
            // the runtime classpath. The Kotlin Toolchain injects the full resolved runtime classpath (including its
            // own kotlin-stdlib) as file dependencies below, which Gradle can't version-reconcile against a
            // coordinate, so AGP's copy would collide with it (checkDuplicateClasses fails). Drop AGP's coordinate stdlib.
            variant.runtimeConfiguration.exclude(mapOf("group" to "org.jetbrains.kotlin", "module" to "kotlin-stdlib"))

            // set dependencies
            for (dependency in requestedModule.resolvedAndroidRuntimeDependencies) {
                variant.runtimeConfiguration.dependencies.add(
                    ResolvedAmperDependency(
                        project,
                        dependency
                    )
                )
            }

            // set inter-module dependencies between android modules
            val androidDependencyPaths = if (project.gradle.knownModel != null) {
                androidFragment
                    .externalDependencies
                    .asSequence()
                    .filterIsInstance<LocalModuleDependency>()
                    .map { it.module }
                    .filter { it.artifacts.any { artifact -> Platform.ANDROID in artifact.platforms } }
                    .mapNotNull { project.gradle.moduleFilePathToProject[it.buildDir] }
                    .filter { it in requestedModules }
                    .toList()
            } else {
                emptyList()
            }

            for (path in androidDependencyPaths) {
                variant.runtimeConfiguration.dependencies.add(project.dependencies.project(mapOf("path" to path)))
            }

            // NOTE: AndroidModuleData.moduleClasses is always empty in the delegated build (the Kotlin Toolchain CLI
            // passes emptyList()), so the legacy registerPostJavacGeneratedBytecode() call was already a no-op. AGP 9's
            // new variant API has no direct equivalent; if prebuilt classes ever need to be injected again, use
            // variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT).use(task).toAppend(ScopedArtifact.CLASSES, ...).
        }
    }

    private fun Project.setArtifactBaseName() {
        val baseExtension = extensions.findByType(BasePluginExtension::class.java) ?: return
        baseExtension.archivesName.set("gradle-project") // The IDE now relies on this name
    }
}

class AmperAndroidIntegrationSettingsPlugin @Inject constructor(private val toolingModelBuilderRegistry: ToolingModelBuilderRegistry) :
    Plugin<Settings> {
    override fun apply(settings: Settings) {
        toolingModelBuilderRegistry.register(ProcessResourcesProviderTaskNameToolingModelBuilder())
        toolingModelBuilderRegistry.register(MockableJarModelBuilder())
        val extension = settings.extensions.create("androidData", AmperAndroidIntegrationExtension::class.java)

        settings.gradle.settingsEvaluated {
            val request = Json.decodeFromString<AndroidBuildRequest>(extension.jsonData.get())
            settings.gradle.request = request
            initProjects(request.root, settings)
        }

        settings.gradle.beforeProject { project ->
            adjustXmlFactories()
            settings.gradle.projectPathToModule[project.path]?.let {
                project.plugins.apply(AmperAndroidIntegrationProjectPlugin::class.java)
            }
        }
    }

    private fun initProjects(projectRoot: Path, settings: Settings) {
        // TODO Instead of importing the Amper model, we could pass the information we need from the Amper CLI.
        //   The interface between the Amper CLI and the Gradle delegate project would be more clearly defined,
        //   and we could use just the relevant subset of the data.
        //   Some pieces of data might even have already been resolved/changed in the Amper CLI, such as dependencies.
        //   and in that case we wouldn't want Gradle to re-read the Amper model files and get it wrong.
        //   Also, it would avoid parsing all modules files in the entire project for each delegated Gradle build.
        // Problems are already reported when running the Amper CLI, so we shouldn't report them again
        val model = with(NoopProblemReporter) {
            val projectContext = AmperProjectContext.create(rootDir = projectRoot, buildDir = null)
                ?: error("Invalid project root passed to the delegated Android Gradle build: $projectRoot")
            projectContext.readProjectModel(
                // We do not recover/pass plugin data here,
                // as it currently can't influence project configuration (besides tasks)
                // Any "unknown property" errors are going to be ignored here.
                pluginData = emptyList(),
                mavenPluginXmls = emptyList(),
            )
        }

        settings.gradle.knownModel = model

        val rootPath = projectRoot.normalize().toAbsolutePath()
        val androidModules = model
            .modules
            .filter {
                val productTypeIsAndroidApp = it.type == ProductType.ANDROID_APP
                val productTypeIsLib = it.type == ProductType.KMP_LIB
                val platformsContainAndroid = it.artifacts.any { artifact -> artifact.platforms.contains(Platform.ANDROID) }
                productTypeIsAndroidApp || productTypeIsLib && platformsContainAndroid
            }
            .sortedBy { it.buildFile }

        fun Path.toGradlePath() = ":" + relativeTo(rootPath).toString().replace(File.separator, ":")

        androidModules.forEach {
            val currentPath = it.buildDir.normalize().toAbsolutePath()
            // A module at the project root can't be delegated to the root Gradle project: AGP 9+ refuses to be
            // applied to the root build file. We delegate it to a synthetic subproject instead (whose projectDir
            // still points to the Kotlin Toolchain project root). All other modules keep their natural Gradle path.
            val projectPath = if (currentPath.isSameFileAs(rootPath)) {
                SYNTHETIC_ROOT_ANDROID_PROJECT_PATH
            } else {
                currentPath.toGradlePath()
            }

            settings.include(projectPath)
            val project = settings.project(projectPath)
            project.projectDir = it.buildDir.toFile()

            /*
            * AndroidLint in AGP 8.6.X+ now checks if build files exist. For each Amper submodule needed to build
            * an app, we explicitly set the build file (using an internal API) in a synthetic Gradle project within
            * the build folder.
            */
            project.buildFileName = currentPath
                .relativize(settings.rootDir.toPath() / "build.gradle.kts")
                .toString()

            // Kotlin Toolchain only includes leaf modules, but a nested module path (e.g. ":app:androidApp") makes Gradle
            // implicitly create container projects for the intermediate path segments (":app"). Those containers
            // inherit a default projectDir under the synthetic Gradle root that doesn't exist on disk, and Gradle 9
            // fails the build for any project whose directory is missing. Point each container at its real source
            // directory (an ancestor of the module dir, which always exists).
            var containerProject = project.parent
            var containerDir = it.buildDir.parent
            while (containerProject != null && containerProject != settings.rootProject) {
                containerProject.projectDir = containerDir.toFile()
                containerProject = containerProject.parent
                containerDir = containerDir.parent
            }

            settings.gradle.projectPathToModule[projectPath] = it
            settings.gradle.moduleFilePathToProject[it.buildDir] = projectPath
        }
    }
}

fun trySetSystemProperty(key: String, value: String) {
    if (System.getProperty(key) == null)
        System.setProperty(key, value)
}

fun adjustXmlFactories() {
    trySetSystemProperty(
        XMLInputFactory::class.qualifiedName!!,
        "com.sun.xml.internal.stream.XMLInputFactoryImpl"
    )
    trySetSystemProperty(
        XMLOutputFactory::class.qualifiedName!!,
        "com.sun.xml.internal.stream.XMLOutputFactoryImpl"
    )
    trySetSystemProperty(
        XMLEventFactory::class.qualifiedName!!,
        "com.sun.xml.internal.stream.events.XMLEventFactoryImpl"
    )
}
