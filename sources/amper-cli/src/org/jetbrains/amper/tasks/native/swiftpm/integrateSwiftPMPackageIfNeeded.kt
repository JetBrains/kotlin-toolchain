/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.native.swiftpm

import com.github.ajalt.mordant.terminal.Terminal
import com.intellij.openapi.util.text.StringUtil
import com.jetbrains.cidr.xcode.model.PBXFrameworksBuildPhase
import com.jetbrains.cidr.xcode.model.PBXNativeTarget
import com.jetbrains.cidr.xcode.model.PBXObject
import com.jetbrains.cidr.xcode.model.PBXProjectFile
import com.jetbrains.cidr.xcode.model.PBXShellScriptBuildPhase
import com.jetbrains.cidr.xcode.model.XCSwiftPackageProductDependency
import com.jetbrains.cidr.xcode.model.addObject
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.tasks.native.swiftpm.GenerateSwiftPMImportPackageTask.Companion.SYNTHETIC_IMPORT_TARGET_MAGIC_NAME

// Copypasted from a more recent IJ to support array or string shellScript content
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

fun integrateSwiftPMPackageIfNeeded(
    swiftPMDependenciesArtifact: SwiftPMDependenciesArtifact,
    project: PBXProjectFile,
    terminal: Terminal,
): Boolean {
    if (!swiftPMDependenciesArtifact.swiftPMDependencies.hasDirectOrTransitiveSwiftPMDependencies) {
        return false
    }
    if (linkageProductsReferencedInPBXObjects(project).isNotEmpty()) {
        terminal.println("Product already referenced, nothing to do")
        return false
    }

    val embedAndSignShellScriptPhases = project.objects.filterIsInstance<PBXShellScriptBuildPhase>().filter {
        "xcode-integration" in it.shellScript()
    }
    if (embedAndSignShellScriptPhases.isEmpty()) {
        userReadableError("Couldn't find Xcode integration")
    }

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
    return true
}