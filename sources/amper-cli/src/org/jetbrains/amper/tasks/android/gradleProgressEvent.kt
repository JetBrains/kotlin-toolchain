/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.android

import org.gradle.tooling.events.FailureResult
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.ProgressEvent
import org.gradle.tooling.events.StartEvent
import org.gradle.tooling.events.SuccessResult
import org.gradle.tooling.events.problems.Details
import org.gradle.tooling.events.problems.ProblemAggregationEvent
import org.gradle.tooling.events.problems.ProblemEvent
import org.gradle.tooling.events.problems.Severity.ERROR
import org.gradle.tooling.events.problems.Severity.WARNING
import org.gradle.tooling.events.problems.SingleProblemEvent
import org.jetbrains.amper.cli.logging.infoNoConsole
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val logger = LoggerFactory.getLogger(object {}.javaClass)

internal fun ProgressEvent.handle(stdoutPath: Path, stderrPath: Path) {
    @Suppress("UnstableApiUsage")
    if (this is ProblemEvent) log(logger)

    if (descriptor.name == "Run build") {
        when (this) {
            is StartEvent -> logger.infoNoConsole("Gradle build started")
            is FinishEvent -> when (val r = result) {
                is SuccessResult -> logger.infoNoConsole("Gradle build finished successfully")
                is FailureResult -> {
                    logger.error("Gradle build failed with errors:")
                    for (error in r.failures) {
                        val causes = ArrayDeque(error.causes)
                        while (causes.isNotEmpty()) {
                            val cause = causes.removeFirst()
                            logger.error(cause.message)
                            causes.addAll(cause.causes)
                        }
                    }

                    logger.error("See more details in the log files:")
                    logger.error("  stdout: $stdoutPath")
                    logger.error("  stderr: $stderrPath")
                }
            }
        }
    }
}

@Suppress("UnstableApiUsage")
internal fun ProblemEvent.log(logger: Logger) {
    val [definition, messages] = when (this) {
        is SingleProblemEvent -> problem.definition to [
            message(problem.contextualLabel?.contextualLabel ?: problem.definition.id.displayName, problem.details),
        ]
        is ProblemAggregationEvent -> problemAggregation.definition to problemAggregation.problemContext.map {
            message(problemAggregation.definition.id.displayName, it.details)
        }
        else -> return
    }
    val loggingFunc: (String) -> Unit = when {
        // Deprecations come from AGP or KGP inside the Gradle build we generate, so users can't act on them—debug level
        // is more than enough.
        definition.id.group.name == "deprecation" -> logger::debug
        definition.severity == WARNING -> logger::warn
        definition.severity == ERROR -> logger::error
        else -> logger::info
    }
    messages.distinct().forEach(loggingFunc)
}

@Suppress("UnstableApiUsage")
private fun message(label: String?, details: Details?): String =
    listOfNotNull(label, details?.details).joinToString(" ")
