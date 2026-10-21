/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */
package org.jetbrains.amper.cidetection

enum class CISystem {
    AppVeyor,
    AwsCodeBuild,
    AzurePipelines,
    BitBucket,
    BuildKite,
    CircleCI,
    GitHub,
    GitLab,
    Jenkins,
    TeamCity,
    TravisCI,
    Other;

    companion object {
        /**
         * Returns the [CISystem] that the current process is running in, or `null` if not running in a CI
         * environment.
         */
        fun detect(): CISystem? = when {
            isEnvSetAndNonFalsy("GITHUB_ACTIONS") -> GitHub
            isEnvSetAndNonFalsy("GITLAB_CI") -> GitLab
            isEnvSetAndNonFalsy("JENKINS_HOME") -> Jenkins
            isEnvSetAndNonFalsy("TF_BUILD") -> AzurePipelines // formerly known as Team Foundation Server, thus the TF_
            isEnvSetAndNonFalsy("TEAMCITY_VERSION") -> TeamCity
            isEnvSetAndNonFalsy("CODEBUILD_BUILD_ID") -> AwsCodeBuild
            isEnvSetAndNonFalsy("BITBUCKET_BUILD_NUMBER") -> BitBucket
            isEnvSetAndNonFalsy("TRAVIS") -> TravisCI
            isEnvSetAndNonFalsy("CIRCLECI") -> CircleCI
            isEnvSetAndNonFalsy("APPVEYOR") -> AppVeyor
            isEnvSetAndNonFalsy("BUILDKITE") -> BuildKite
            // must be last
            isEnvSetAndNonFalsy("CI") -> Other
            else -> null
        }
    }
}

private val FalsyValues = setOf("false", "0", "no", "off", "none")

private fun isEnvSetAndNonFalsy(name: String): Boolean {
    val nonBlankVar = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return nonBlankVar.lowercase() !in FalsyValues
}
