/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli

import org.jetbrains.amper.cli.context.CliContext
import org.jetbrains.amper.cli.widgets.status.standaloneOperationProgressWidget
import org.jetbrains.amper.events.sink.operationEventScope
import org.jetbrains.amper.jvm.FreestandingJdkProvisioning
import org.jetbrains.amper.jvm.getDefaultJdk

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