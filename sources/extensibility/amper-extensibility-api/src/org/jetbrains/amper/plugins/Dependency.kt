/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.plugins

import java.nio.file.Path

/**
 * Dependency sealed interface.
 */
@Configurable
sealed interface Dependency {
    /**
     * A dependency on a local module in the project. 
     * Can be constructed from a path string, like `../module-name` or `"."`.
     * If not started with `"."` then it's treated like an external maven dependency.
     */
    @Configurable
    interface Local : Dependency {
        /**
         * Path to the module root directory.
         */
        @PathValueOnly
        val modulePath: Path
    }

    /**
     * External Maven dependency. 
     * Can be constructed from Maven coordinates string, like `com.example:artifact:1.0.0`, 
     * or from a full YAML form, like:
     * ```yaml
     * groupId: com.example
     * artifactId: artifact
     * version: 1.0.0
     * ```
     */
    @Configurable
    interface Maven : Dependency {
        /**
         * External Maven artifact groupId.
         */
        val groupId: String

        /**
         * External Maven artifact artifactId.
         */
        val artifactId: String

        /**
         * External Maven artifact version.
         */
        val version: String

        /**
         * Optional Maven artifact classifier. Empty by default.
         */
        val classifier: String?

        /**
         * Optional Maven artifact packaging type. Jar by default.
         */
        val packagingType: String?
    }
}
