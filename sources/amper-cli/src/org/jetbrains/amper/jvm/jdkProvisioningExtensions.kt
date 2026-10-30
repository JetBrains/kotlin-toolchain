/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.jvm

import org.jetbrains.amper.cli.context.CliContext
import org.jetbrains.amper.cli.userReadableError
import org.jetbrains.amper.cli.widgets.status.standaloneOperationProgressWidget
import org.jetbrains.amper.events.sink.OperationEventSink
import org.jetbrains.amper.events.sink.operationEventScope
import org.jetbrains.amper.frontend.schema.DefaultVersions
import org.jetbrains.amper.frontend.schema.DiscouragedDirectDefaultVersionAccess
import org.jetbrains.amper.frontend.schema.JdkSelectionMode
import org.jetbrains.amper.jdk.provisioning.Jdk
import org.jetbrains.amper.jdk.provisioning.JdkProvider
import org.jetbrains.amper.jdk.provisioning.JdkProvisioningCriteria
import org.jetbrains.amper.jdk.provisioning.orElse
import org.jetbrains.amper.problems.reporting.ProblemReporter

@RequiresOptIn(
    message = "If you are using this outside of a task, consider using `getDefaultJdkInCommand`",
    level = RequiresOptIn.Level.WARNING,
)
annotation class FreestandingJdkProvisioning

/**
 * Finds or provisions a JDK matching the default settings, or fails with a [userReadableError].
 *
 * This JDK is what users get when a module uses the default JDK settings.
 *
 * Potential global errors about `JAVA_HOME` are reported via the given [ProblemReporter], but only once per
 * instance of [JdkProvider].
 */
@FreestandingJdkProvisioning
@OptIn(DiscouragedDirectDefaultVersionAccess::class) // this is the point of this function
context(problemReporter: ProblemReporter, sink: OperationEventSink)
suspend fun JdkProvider.getDefaultJdk(): Jdk = getJdk(
    criteria = JdkProvisioningCriteria(majorVersion = DefaultVersions.jdk),
    selectionMode = JdkSelectionMode.auto,
).orElse { errorMessage ->
    userReadableError("Could not provide the default JDK: $errorMessage")
}

/**
 * Provides default JDK outside the task graph invocation,
 * e.g. directly in a CLI command implementation where no backend is instantiated.
 *
 * @see getDefaultJdk
 */
@OptIn(FreestandingJdkProvisioning::class) // this is the point of this function
suspend fun getDefaultJdkInCommand(context: CliContext) = standaloneOperationProgressWidget(context.terminal) {
    context(context.problemReporter) {
        operationEventScope("provisioning default JDK") {
            context.jdkProvider.getDefaultJdk()
        }
    }
}