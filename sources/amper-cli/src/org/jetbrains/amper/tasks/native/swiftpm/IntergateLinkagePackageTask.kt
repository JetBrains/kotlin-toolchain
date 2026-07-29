/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native.swiftpm

import com.intellij.openapi.util.text.StringUtil
import com.jetbrains.cidr.xcode.XcodeProjectId
import com.jetbrains.cidr.xcode.model.PBXFrameworksBuildPhase
import com.jetbrains.cidr.xcode.model.PBXNativeTarget
import com.jetbrains.cidr.xcode.model.PBXObject
import com.jetbrains.cidr.xcode.model.PBXProjectFile
import com.jetbrains.cidr.xcode.model.PBXShellScriptBuildPhase
import com.jetbrains.cidr.xcode.model.ProjectFilesChanges
import com.jetbrains.cidr.xcode.model.XCSwiftPackageProductDependency
import com.jetbrains.cidr.xcode.model.addObject
import com.jetbrains.cidr.xcode.util.XcodeUserDataHolder
import fleet.com.intellij.openapi.util.UserDataHolderEx
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.tasks.EmptyTaskResult
import org.jetbrains.amper.tasks.TaskResult
import org.jetbrains.amper.tasks.ios.initializeXcodeComponentManager
import org.jetbrains.amper.tasks.ios.xcodeprojPath
import org.jetbrains.amper.tasks.native.swiftpm.SwiftPMImportTask.Companion.SYNTHETIC_IMPORT_TARGET_MAGIC_NAME

class IntegrateLinkagePackageTask(
    val module: AmperModule,
    override val taskName: TaskName,
) : Task {
    private class XcodeProjectHandle : XcodeProjectId, UserDataHolderEx by XcodeUserDataHolder()

    // Copypasted from a more recent IJ
    fun PBXShellScriptBuildPhase.shellScript(): String {
        val script = getAttribute("shellScript", Any::class.java, null)

        var shellScript: String? = null
        if (script == null) return ""
        if (script is String) {
            shellScript = StringUtil.unquoteString(script.trim { it <= ' ' })
        } else if (script is MutableList<*>) {
            for (each in script) {
                checked(each, String::class.java)
            }
            shellScript = StringUtil.unquoteString(StringUtil.join(script, "\n"))
        }
        return shellScript ?: ""
    }

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult {
        initializeXcodeComponentManager()
        val projectPath = module.xcodeprojPath()

        val project = PBXProjectFile (XcodeProjectHandle(), projectPath, projectPath.resolve("project.pbxproj"))
        project.load(ProjectFilesChanges())
        if (linkageProductsReferencedInPBXObjects(project).isNotEmpty()) {
            println("Product already referenced, nothing to do")
            return EmptyTaskResult
        }

        val embedAndSignShellScriptPhases = project.objects.filterIsInstance<PBXShellScriptBuildPhase>().filter {
            "xcode-integration" in it.shellScript()
        } ?: error("couldn't find Xcode integration")
        val embedAndSignTargets = project.objects.filterIsInstance<PBXNativeTarget>().filter { target ->
            embedAndSignShellScriptPhases.any { it in target.buildPhases }
        }

        val localPackageDependency = PBXObject(project)
        localPackageDependency.setAttribute("isa", "XCLocalSwiftPackageReference")
        localPackageDependency.setAttribute("relativePath", SYNTHETIC_IMPORT_TARGET_MAGIC_NAME)

        val productDependency = XCSwiftPackageProductDependency(project)
        productDependency.setAttribute("productName", SYNTHETIC_IMPORT_TARGET_MAGIC_NAME)

        project.addObject(localPackageDependency)
        project.addObject(productDependency)

        val buildFileDependency = PBXObject(project)
        buildFileDependency.setAttribute("isa", "PBXBuildFile")
        buildFileDependency.setAttribute("productRef", productDependency.createReference())
        project.addObject(buildFileDependency)

        val buildFileDependencyReference = buildFileDependency.createReference()

        // FIXME: Check if this is trully needed, Xcode adds this
        project.projectObject.addToAttributeList("packageReferences", localPackageDependency.createReference())
        embedAndSignTargets.forEach {
            project.manipulator.addSwiftPackageProductDependency(it, productDependency)
        }

        val frameworkPhases = embedAndSignTargets.flatMap {
            it.buildPhases.filterIsInstance<PBXFrameworksBuildPhase>()
        }
        frameworkPhases.forEach {
            it.addFile(buildFileDependencyReference)
        }

        project.lock()
        project.save(true)

        return EmptyTaskResult
    }
}