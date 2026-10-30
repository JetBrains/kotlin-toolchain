/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.jvm

import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.engine.Task
import org.jetbrains.amper.engine.TaskGraphExecutionContext
import org.jetbrains.amper.engine.TaskName
import org.jetbrains.amper.frontend.schema.JdkSettings
import org.jetbrains.amper.jdk.provisioning.Jdk
import org.jetbrains.amper.jdk.provisioning.JdkProvider
import org.jetbrains.amper.jdk.provisioning.orElse
import org.jetbrains.amper.tasks.TaskResult

/**
 * Finds or provisions a JDK matching the given [jdkSettings].
 *
 * This is a project-scoped task: a single instance is shared by all modules having equivalent JDK settings.
 */
internal class ProvideJdkTask(
    private val jdkSettings: JdkSettings,
    private val jdkProvider: JdkProvider,
) : Task {

    override val taskName: TaskName = provideJdkTaskName(jdkSettings)

    context(executionContext: TaskGraphExecutionContext)
    override suspend fun run(dependenciesResult: List<TaskResult>): TaskResult = Result(
        jdk = context(executionContext.eventSink) {
            jdkProvider.getJdk(jdkSettings).orElse { errorMessage ->
                userReadableError(errorMessage)
            }
        }
    )

    class Result(val jdk: Jdk) : TaskResult
}

/**
 * The name of the [ProvideJdkTask] for the given [jdkSettings].
 * Equivalent settings produce the same task id, so the JDK is provided once per distinct settings.
 */
internal fun provideJdkTaskName(jdkSettings: JdkSettings): TaskName = TaskName(
    internalName = jdkSettings.encodeToInternalName(),
    operationMoniker = "provisioning JDK ${jdkSettings.version}",
)

private fun JdkSettings.encodeToInternalName(): String {
    // the order of distributions matters (it's a priority), so it is preserved
    val distributionsPart = distributions?.joinToString("-") { it.value.schemaValue } ?: "any"
    val licensesPart = acknowledgedLicenses.map { it.schemaValue }.distinct().sorted().joinToString("-")
    val licensesSuffix = if (licensesPart.isEmpty()) "" else "-licenses-$licensesPart"
    return "provideJdk$version-${selectionMode.schemaValue}-distributions-$distributionsPart$licensesSuffix"
}
