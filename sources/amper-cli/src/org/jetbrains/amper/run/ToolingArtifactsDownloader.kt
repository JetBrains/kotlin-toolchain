/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.run

import org.jetbrains.amper.CliReportingMavenResolver
import org.jetbrains.amper.core.AmperUserCacheRoot
import org.jetbrains.amper.dependency.resolution.MavenCoordinates
import org.jetbrains.amper.dependency.resolution.MavenRepository
import org.jetbrains.amper.dependency.resolution.MavenRepository.Companion.MavenCentral
import org.jetbrains.amper.dependency.resolution.Repository
import org.jetbrains.amper.dependency.resolution.ResolutionPlatform
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.frontend.aomBuilder.GOOGLE_REPOSITORY_URL
import org.jetbrains.amper.frontend.dr.resolver.toIncrementalCacheResult
import org.jetbrains.amper.frontend.processing.KOTLIN_TOOLCHAIN_REPOSITORY_URL
import org.jetbrains.amper.frontend.schema.DefaultVersions
import org.jetbrains.amper.frontend.schema.DiscouragedDirectDefaultVersionAccess
import org.jetbrains.amper.incrementalcache.IncrementalCache
import org.jetbrains.amper.problems.reporting.ProblemReporter
import org.jetbrains.amper.system.info.SystemInfo
import java.nio.file.Path

val GOOGLE_REPOSITORY = MavenRepository(GOOGLE_REPOSITORY_URL)
val AMPER_DEV_REPOSITORY = MavenRepository(KOTLIN_TOOLCHAIN_REPOSITORY_URL)

class ToolingArtifactsDownloader(
    userCacheRoot: AmperUserCacheRoot,
    private val incrementalCache: IncrementalCache,
) {
    private val mavenResolver = CliReportingMavenResolver(userCacheRoot, incrementalCache)

    context(_: ProblemReporter)
    suspend fun downloadHotReloadAgent(hotReloadVersion: String): List<Path> =
        downloadToolingArtifacts(
            resolveSourceMoniker = "Compose Hot Reload",
            coordinates = [
                MavenCoordinates("org.jetbrains.compose.hot-reload", "hot-reload-agent", hotReloadVersion),
                MavenCoordinates("org.jetbrains.compose.hot-reload", "hot-reload-runtime-jvm", hotReloadVersion),
            ],
        )

    context(_: ProblemReporter)
    suspend fun downloadHotReloadMcp(hotReloadVersion: String): List<Path> =
        downloadToolingArtifacts(
            resolveSourceMoniker = "Compose Hot Reload",
            coordinates = [
                MavenCoordinates("org.jetbrains.compose.hot-reload", "hot-reload-mcp", hotReloadVersion),
            ],
        )

    context(_: ProblemReporter)
    suspend fun downloadDevTools(
        hotReloadVersion: String,
        composeVersion: String,
    ): List<Path> = downloadToolingArtifacts(
        resolveSourceMoniker = "Compose Hot Reload",
        coordinates = [
            MavenCoordinates("org.jetbrains.compose.hot-reload", "hot-reload-devtools", hotReloadVersion),
            MavenCoordinates("org.jetbrains.compose.desktop", "desktop-jvm-${SystemInfo.CurrentHost.familyArch}", composeVersion),
        ],
        repositories = [MavenCentral, GOOGLE_REPOSITORY, AMPER_DEV_REPOSITORY],
    )

    context(_: ProblemReporter)
    suspend fun downloadComposeDesktop(composeVersion: String): List<Path> = downloadToolingArtifacts(
        resolveSourceMoniker = "Compose Hot Reload",
        coordinates = [
            MavenCoordinates(
                "org.jetbrains.compose.desktop",
                "desktop-jvm-${SystemInfo.CurrentHost.familyArch}",
                composeVersion,
            ),
        ],
        repositories = [MavenCentral, GOOGLE_REPOSITORY],
    )

    @OptIn(DiscouragedDirectDefaultVersionAccess::class)
    context(_: ProblemReporter)
    suspend fun downloadSpringBootLoader(jvmRelease: Int): Path = downloadToolingArtifacts(
        resolveSourceMoniker = "executable JAR",
        coordinates = [
            MavenCoordinates(
                groupId = "org.springframework.boot",
                artifactId = "spring-boot-loader",
                // Spring Boot 2 requires minimum Java 8, while Spring Boot 3 and 4 require Java 17
                // Check latest 2.7.x versions there: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-loader/
                // Note: we don't use the user's settings.spring.version here because the Spring loader is an
                // implementation detail of our executable JAR format. We want to control how we use it. The user might
                // not even be using Spring at all.
                version = if (jvmRelease < 17) "2.7.18" else DefaultVersions.springBoot,
            )
        ]
    ).single()

    context(_: ProblemReporter)
    private suspend fun downloadToolingArtifacts(
        /** To refer to this resolution in error messages: "Unable to resolve deps for <moniker>". */
        resolveSourceMoniker: String,
        coordinates: List<MavenCoordinates>,
        repositories: List<Repository> = [MavenCentral],
    ): List<Path> {
        val scope = ResolutionScope.RUNTIME
        val platform = ResolutionPlatform.JVM
        return incrementalCache.execute(
            key = "resolve-$coordinates ($scope, $platform)",
            inputValues = mapOf("repositories" to repositories.joinToString(",")),
            inputFiles = [],
        ) {
            val resolved = mavenResolver.resolve(
                coordinates = coordinates,
                repositories = repositories,
                scope = scope,
                platform = platform,
                resolveSourceMoniker = resolveSourceMoniker,
            )
            return@execute resolved.toIncrementalCacheResult()
        }.outputFiles
    }
}
