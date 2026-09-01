/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli.test.utils

import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import org.jetbrains.amper.system.info.Arch
import org.jetbrains.amper.system.info.OsFamily
import org.jetbrains.amper.system.info.SystemInfo
import org.jetbrains.amper.test.AmperCliResult
import org.jetbrains.amper.test.runTestWithMdc
import org.jetbrains.amper.test.spans.assertHasAttribute
import org.jetbrains.amper.test.spans.javaCompilationSpans
import org.jetbrains.amper.test.spans.withAmperModule
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.time.Duration.Companion.minutes

// Must not be made inline because it surfaces the Kotlin bug IDEA-370092
fun runSlowTest(testBody: suspend TestScope.() -> Unit): TestResult = runTestWithMdc(
    timeout = 15.minutes,
    testBody = testBody,
)

/**
 * The name of the Kotlin/Native leaf platform matching this host system, as accepted by the `--platform` CLI option.
 */
internal fun SystemInfo.nativePlatformName(): String = when (family) {
    OsFamily.FreeBSD,
    OsFamily.Solaris,
    OsFamily.Linux -> when (arch) {
        Arch.X64 -> "linuxX64"
        Arch.Arm64 -> "linuxArm64"
    }
    OsFamily.MacOs -> when (arch) {
        Arch.X64 -> "macosX64"
        Arch.Arm64 -> "macosArm64"
    }
    OsFamily.Windows -> "mingwX64"
}

// FIXME this should never be needed, because task output paths should be internal.
//  User-visible artifacts should be placed in user-visible directories (use some convention).
internal fun AmperCliResult.getTaskOutputPath(taskName: String): Path =
    buildDir / "tasks" / taskName.replace(':', '_')

/**
 * Asserts that the Java compilation was executed in the expected incremental/non-incremental mode.
 */
internal fun AmperCliResult.assertJavaIncrementalCompilationState(
    compileJavaIncrementally: Boolean,
    moduleName: String? = null,
) {
    val javacSpans = readTelemetrySpans().javaCompilationSpans
    val javacSpan = if (moduleName != null) javacSpans.withAmperModule(moduleName) else javacSpans
    javacSpan.assertSingle().assertHasAttribute(AttributeKey.booleanKey("incremental"), compileJavaIncrementally)
}
