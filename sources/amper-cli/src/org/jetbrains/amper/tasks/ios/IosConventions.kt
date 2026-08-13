/*
 * Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.ios

import org.jetbrains.amper.frontend.AmperModule
import org.jetbrains.amper.frontend.schema.ProductType
import java.nio.file.Path
import kotlin.io.path.div

object IosConventions {
    /**
     * the single default Kotlin iOS framework name that is built per ios/app.
     * The name `KotlinModules` is used in Swift imports then.
     */
    const val KOTLIN_FRAMEWORK_NAME = "KotlinModules.framework"

    /**
     * Compose Resources directory name inside the app bundle
     * that the compose-resources runtime library expects.
     */
    const val COMPOSE_RESOURCES_CONTENT_DIR_NAME = "compose-resources"

    /**
     * Framework directory name that the Xcode project has to know about.
     * This is used in Xcode project generation for linker search paths.
     */
    const val FRAMEWORKS_DIR_NAME = "AmperFrameworks"

    /**
     * Shared scheme name that is used to build the iOS app.
     */
    const val SCHEME_NAME = "app"

    /**
     * Name of the directory of the xcode project.
     * @see xcodeProjectPath
     */
    const val XCODE_PROJECT_DIRECTORY_NAME = "module.xcodeproj"
}

/**
 * Path to the Xcode project for an `ios/app` module.
 */
val AmperModule.xcodeProjectPath: Path
    get() {
        require(type == ProductType.IOS_APP) {
            "Expected ${ProductType.IOS_APP}, got $type"
        }

        return source.moduleDir / IosConventions.XCODE_PROJECT_DIRECTORY_NAME
    }
