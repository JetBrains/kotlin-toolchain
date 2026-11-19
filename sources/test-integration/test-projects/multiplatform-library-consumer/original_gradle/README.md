# Multiplatform library template

This Gradle multiplatform project was ported to Kotlin Toolchain as a project [multiplatform-library-consumer](..).

This project can be used to get gold files used for the Kotlin Toolchain tests of KMP publication. 

To generate original publication gold files:

1. run `./gradlew publishToMavenLocal -x :edgeCase_noSources:publishLinuxX64PublicationToMavenLocal` in
   [the producer Gradle project](../../multiplatform-library-template-main/original_gradle) — the excluded task is the
   only publication of the producer that KGP cannot generate, and the whole build fails without the exclusion
2. run `./gradlew publishToMavenLocal` in this project
