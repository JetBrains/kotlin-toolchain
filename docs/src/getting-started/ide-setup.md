---
description: This page describes how to set up your IDE to work with the Kotlin Toolchain.
---
# IDE Setup

??? question "Do I need to use IntelliJ IDEA?"

    The Kotlin Toolchain includes a command line tool that stands on its own, so using IntelliJ IDEA is not required.
    If you prefer to work directly with the terminal or in another IDE, check out the [Kotlin CLI](../cli/index.md).

    However, to make the most out of the Kotlin Toolchain and its toolability, we recommend using IntelliJ IDEA.
    There are tons of diagnostics and quick fixes that make your life a bliss when working with the Kotlin Toolchain.

1. Preferably use the latest [:jetbrains-intellij-idea: IntelliJ IDEA EAP](https://www.jetbrains.com/idea/nextversion/). 
   The best way to get the most recent IDE versions is by using the [:jetbrains-toolbox-app: Toolbox App](https://www.jetbrains.com/lp/toolbox/).

2. Make sure to install the [:jetbrains-amper: Kotlin Toolchain plugin](https://plugins.jetbrains.com/plugin/31850-kotlin-toolchain):

   ![](../images/ij-plugin.png)

3. [Optional] If you want to write code for :material-apple: Apple platforms or share code between several platforms, 
   install the [:jetbrains-kotlin-multiplatform: Kotlin Multiplatform plugin](https://plugins.jetbrains.com/plugin/14936-kotlin-multiplatform).

4. [Optional] If you want to write some Android-specific code, also install the
   [:android-head-flat: Android plugin](https://plugins.jetbrains.com/plugin/22989-android).
