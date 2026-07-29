/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.schema.swiftpm

import org.jetbrains.amper.swiftpm.SwiftPMDependency
import java.nio.file.Path

private fun convertLocalPackagePath(path: Path, resolvingModulePath: Path): Path {
    // just in case, normalize the path because we do package name inference on the filename
    val normalizedPath = path.normalize()
    if (normalizedPath.isAbsolute) return normalizedPath
    return resolvingModulePath.resolve(normalizedPath).toAbsolutePath()
}

fun SwiftPMDependencySchema.Local.convertSchema(resolvingModulePath: Path): SwiftPMDependency.Local = SwiftPMDependency.Local(
    absolutePath = convertLocalPackagePath(path = path, resolvingModulePath = resolvingModulePath),
    products = products.map {
        SwiftPMDependency.Product(
            name = it,
            cinteropClangModules = emptyList(),
            platformConstraints = emptyList(),
        )
    },
    cinteropClangModules = emptyList(),
    packageName = packageName,
    traits = traits
)

fun SwiftPMDependencySchema.Remote.convertSchema(): SwiftPMDependency.Remote = SwiftPMDependency.Remote(
    repository = repository.convertSchema(),
    version = version.convertSchema(),
    products = products.map {
        SwiftPMDependency.Product(
            name = it,
            cinteropClangModules = emptyList(),
            platformConstraints = emptyList(),
        )
    },
    cinteropClangModules = emptyList(),
    packageName = packageName,
    traits = traits
)

fun SwiftPMDependencySchema.convertSchema(): SwiftPMDependency {
    return when (this) {
        is SwiftPMDependencySchema.Local -> convertSchema()
        is SwiftPMDependencySchema.Remote -> convertSchema()
    }
}

fun SwiftPMDependencySchema.Remote.Repository.convertSchema(): SwiftPMDependency.Remote.Repository = when (this) {
    is SwiftPMDependencySchema.Remote.Repository.Url -> SwiftPMDependency.Remote.Repository.Url(value)
    is SwiftPMDependencySchema.Remote.Repository.Id -> SwiftPMDependency.Remote.Repository.Id(value)
}

fun SwiftPMDependencySchema.Remote.Version.convertSchema(): SwiftPMDependency.Remote.Version = when (this) {
    is SwiftPMDependencySchema.Remote.Version.From -> SwiftPMDependency.Remote.Version.From(value)
    is SwiftPMDependencySchema.Remote.Version.Exact -> SwiftPMDependency.Remote.Version.Exact(value)
    is SwiftPMDependencySchema.Remote.Version.Branch -> SwiftPMDependency.Remote.Version.Branch(value)
    is SwiftPMDependencySchema.Remote.Version.Revision -> SwiftPMDependency.Remote.Version.Revision(value)
    is SwiftPMDependencySchema.Remote.Version.Range -> TODO()
}