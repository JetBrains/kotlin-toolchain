/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.diagnostics

import org.jetbrains.amper.frontend.Model
import org.jetbrains.amper.frontend.asBuildProblemSource
import org.jetbrains.amper.frontend.reportBundleError
import org.jetbrains.amper.problems.reporting.Level
import org.jetbrains.amper.problems.reporting.ProblemReporter
import java.nio.file.Path

/**
 * Reports local Swift packages that live outside the project directory.
 *
 * Such a package is not part of the project, so it is not shared with it: anyone who checks out the project on another
 * machine can only resolve the dependency by reproducing the same directory layout. This is independent of publishing,
 * which has its own (stricter) rule in [LocalSwiftPackageInRemotePublicationFactory]: a published local Swift package
 * is unusable for consumers even when it lives inside the project.
 *
 * This is only a warning, because the build works on the machine that declared the package, and pointing outside the
 * project can be deliberate (for example, when working on a project and a package side by side).
 *
 * Note that this does not catch an absolute path that happens to point *inside* the project: paths are resolved
 * against the module directory while reading the module, so at this point such a path is indistinguishable from a
 * relative one.
 */
object LocalSwiftPackageOutsideProjectFactory : AomModelDiagnosticFactory {

    override fun analyze(model: Model, problemReporter: ProblemReporter) {
        val projectRoot = model.projectRoot.normalizedAbsolute()
        model.modules.forEach { module ->
            module.declaredLocalSwiftPackages()
                .filterNot { it.swiftPMDependency.absolutePath.normalizedAbsolute().startsWith(projectRoot) }
                .forEach { notation ->
                    problemReporter.reportBundleError(
                        source = notation.asBuildProblemSource(),
                        diagnosticId = FrontendDiagnosticId.LocalSwiftPackageOutsideProject,
                        messageKey = "dependencies.swiftpm.local.outside.project",
                        notation.swiftPMDependency.packageName,
                        level = Level.Warning,
                    )
                }
        }
    }
}

private fun Path.normalizedAbsolute(): Path = toAbsolutePath().normalize()
