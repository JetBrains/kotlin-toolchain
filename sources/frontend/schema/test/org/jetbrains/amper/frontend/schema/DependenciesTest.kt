/*
 * Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.schema

import org.jetbrains.amper.frontend.helpers.FrontendTestCaseBase
import org.jetbrains.amper.frontend.helpers.aomTest
import org.junit.jupiter.api.Tag
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.test.Test

@Tag("gold-file")
internal class DependenciesTest : FrontendTestCaseBase(Path("testResources") / "dependencies") {

    @Test
    fun exported() {
        aomTest("dependency-flags-exported")
    }

    @Test
    fun `compile runtime`() {
        aomTest("dependency-flags-runtime-compile")
    }
    
    /**
     * See: https://youtrack.jetbrains.com/issue/AMPER-3619
     */
    @Test
    fun `dependencies for non-complete ios fragment`() {
        aomTest("non-complete-ios-fragment")
    }

    @Test
    fun `bom dependency`() {
        aomTest("bom-dependency")
    }

    @Test
    fun `dependency with classifier`() {
        aomTest("dependency-with-classifier")
    }

    @Test
    fun `dependency with packagingType`() {
        aomTest("dependency-with-packagingType")
    }

    /**
     * This test is very important, it checks a current design invariant:
     * dependency declared for a main fragment is applied to ALL fragments
     * which platforms set is a subset of that fragment platforms.
     *
     * The invariant is obvious about refining/friends fragments,
     * but it holds as well for:
     * - sibling fragments (with the same set of platforms)
     * - and for the fragments this fragment refines (if platforms set is equal)
     *
     * In particular,
     * `notJvm` and `native` declare different dependencies and are siblings - none of them refines each other,
     * but both end up having the same dependencies.
     *
     * Kotlin Toolchain module dependency resolution relies on this invariant at the moment.
     * It resolves dependencies for the following resolution context { scope, platforms set, isTest } once
     * and reuses the result for all fragments with the same context.
     *
     * As soon as fragments with the same platforms start having different dependencies,
     * dependency resolution logic should be adjusted (if not yet).
     */
    @Test
    fun `fragments with the same platforms share dependencies`() {
        aomTest("kmp-alias-same-platforms-as-native")
    }
}
