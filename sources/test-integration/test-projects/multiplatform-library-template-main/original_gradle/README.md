[![official project](http://jb.gg/badges/official.svg)](https://github.com/JetBrains#jetbrains-on-github)

# Multiplatform library template

This Gradle multiplatform project was ported to Kotlin Toolchain as a project [multiplatform-library-template-main](..)

This project can be used to get the reference files used for the Kotlin Toolchain tests of KMP publication: the
`*.original_gradle.json` files of `test-integration/amper-cli-test/testResources` (`gradleMetadata`,
`kotlinToolingMetadata` and `metadata`). They are not asserted, they are only kept as the reference of what KGP
produces for the same modules.

To regenerate the reference files, run `./gradlew generateOriginalGradleGoldFiles`. It copies the metadata that this
project generates straight from the build outputs, including the ones of `edgeCase_noSources`, whose linuxX64
publication is the only one KGP cannot produce (see `settings.gradle.kts`).

To publish this project's artifacts for the consumer Gradle project to resolve, run
`./gradlew publishToMavenLocal -x :edgeCase_noSources:publishLinuxX64PublicationToMavenLocal` — the whole build fails
without the exclusion, on the publication of `edgeCase_noSources` that KGP cannot generate.
