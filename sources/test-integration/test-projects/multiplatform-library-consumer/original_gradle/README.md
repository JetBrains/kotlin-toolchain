# Multiplatform library template

This Gradle multiplatform project was ported to Kotlin Toolchain as a project [multiplatform-library-consumer](..).

This project can be used to get gold files used for the Kotlin Toolchain tests of KMP publication. 

To generate original publication gold files:

1. run `./gradlew publishToMavenLocal` in [the producer Gradle project](../../multiplatform-library-template-main/original_gradle)
2. run `./gradlew publishToMavenLocal` in this project
