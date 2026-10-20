/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.android.gradle.tooling

import org.gradle.api.Project
import org.gradle.tooling.provider.model.ToolingModelBuilder
import org.jetbrains.amper.android.MockableJarModel
import org.jetbrains.amper.android.gradle.mockableJarArtifacts
import java.io.File


data class DefaultMockableJarModel(override val file: File?) : MockableJarModel


class MockableJarModelBuilder : ToolingModelBuilder {
    override fun canBuild(modelName: String): Boolean = modelName == MockableJarModel::class.java.name

    override fun buildAll(modelName: String, project: Project): MockableJarModel {
        return DefaultMockableJarModel(findMockableJar(project))
    }

    // The mockable android.jar artifact is captured per project during variant configuration (see the plugin's
    // onVariants handler) and resolved here lazily. Under AGP 9, the `androidApis` configuration that previously
    // exposed it doesn't exist at model-query time, so we rely on the unit-test component's classpath instead.
    // The artifact is resolved for the given project only. A model builder holds the lock of its own project only
    // when Gradle runs in parallel mode (e.g., `org.gradle.parallel=true` in the user's gradle.properties), so
    // resolving a configuration of another project fails with "attempted without an exclusive lock" in that mode.
    private fun findMockableJar(project: Project): File? =
        project.gradle.mockableJarArtifacts[project.path]?.files?.firstOrNull()
}
