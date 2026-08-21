/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.android.gradle

import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.FileCollectionDependency
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.api.internal.artifacts.dependencies.SelfResolvingDependencyInternal
import org.gradle.api.internal.tasks.DefaultTaskDependencyFactory
import org.gradle.api.tasks.TaskDependency
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
import org.jetbrains.amper.android.ResolvedDependency
import java.io.File

class ResolvedAmperDependency(private val project: Project, private val flatDependency: ResolvedDependency) : FileCollectionDependency, SelfResolvingDependencyInternal {
    private var _reason: String? = null

    override fun getGroup(): String = LOCAL_MODULE_GROUP

    override fun getName(): String = flatDependency.id

    override fun getVersion(): String = Project.DEFAULT_VERSION

    @Deprecated("Deprecated in Java")
    override fun contentEquals(dependency: Dependency): Boolean =
        dependency.group == group && dependency.name == name && dependency.version == version

    override fun copy(): Dependency = ResolvedAmperDependency(project, flatDependency)

    override fun getReason(): String? = _reason

    override fun because(reason: String?) {
        this._reason = reason
    }

    @Deprecated("Deprecated in Java")
    override fun getBuildDependencies(): TaskDependency = DefaultTaskDependencyFactory.withNoAssociatedProject().visitingDependencies {
    }

    @Deprecated("Deprecated in Java")
    override fun resolve(): MutableSet<File> = mutableSetOf(flatDependency.path.toFile())

    @Deprecated("Deprecated in Java")
    override fun resolve(transitive: Boolean): MutableSet<File> = mutableSetOf(flatDependency.path.toFile())

    /**
     * The identity of this dependency in the resolved dependency graph.
     *
     * Consumers see this as the owner of the resolved variant, and some of them (AGP's Java resource merger, in
     * particular) use it to tell classpath entries apart. This is why the dependency is reported as a module rather
     * than as a plain file: an [OpaqueComponentArtifactIdentifier] only exposes the file *name*, so two artifacts
     * published under different coordinates but with the same file name would be indistinguishable (KTC-5751).
     *
     * [ResolvedDependency.id] is unique within the build, which is all that Gradle and its consumers need from this
     * identifier.
     */
    override fun getTargetComponentId(): ComponentIdentifier = DefaultModuleComponentIdentifier(
        DefaultModuleIdentifier.newId(group, name),
        version,
    )

    override fun getFiles(): FileCollection = project.files(flatDependency.path.toAbsolutePath().toString())
}

/**
 * Prefixes the synthetic coordinates of these dependencies (see [ResolvedAmperDependency.getTargetComponentId]).
 *
 * They are not published modules, just files that Amper has already resolved, so there is nothing to take a real
 * group from.
 */
private const val LOCAL_MODULE_GROUP = "localModule"
