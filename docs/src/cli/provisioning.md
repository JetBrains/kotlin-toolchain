---
description: |
  Part of our philosophy is to avoid the hassle of setting up toolchains, including the JDK and the Kotlin Toolchain 
  itself. This is why we recommend checking the Kotlin wrapper script into your project's root folder.
---
# Wrapper script & provisioning

Part of our philosophy is to avoid the hassle of setting up toolchains, including the JDK and the Kotlin Toolchain itself.

The recommended way to use the Kotlin Toolchain is to check the Kotlin wrapper script into your project's root folder, so that anyone 
cloning your project can just run `./kotlin build` and start working right away — that's it.
No installation needed, no matter their OS.

## What's the wrapper script?

The **Kotlin wrapper script** is a small file (`kotlin` or `kotlin.bat`) that downloads and runs the actual Kotlin CLI 
application[^1], and serves as an entry point for all Kotlin CLI commands.

Of course, the Kotlin CLI application is only downloaded once (per version) and subsequent calls to the wrapper 
immediately delegate to it.

[^1]: The Kotlin CLI is, at the moment, a JVM application. The Kotlin Toolchain distribution is therefore a bunch of JAR files, and
      they need a Java Runtime Environment (JRE) to run. This is an implementation detail and may change in the future,
      so you should not rely on it.

## Concurrency

The provisioning mechanism and all relevant behaviors in the Kotlin Toolchain are designed to be safe to use concurrently.
This means you can run as many Kotlin CLI commands as you want in parallel, and they won't disturb each other.

## Loading environment files

Use the `--env-file` root option to load environment variables before the Kotlin CLI initializes the project or executes
build tasks. The path is relative to the current working directory and can use any name, such as `.env.dev` or `.env.prod`:

```shell
./kotlin --env-file .env.dev run -m server
./kotlin --env-file .env.prod package -m server
```

The option can be repeated to layer environment-specific values over shared defaults:

```shell
./kotlin --env-file .env --env-file .env.dev run -m server
```

Variables already present in the process environment take precedence over files. When multiple files are supplied, an
environment file supplied later takes precedence over earlier files. Environment files support blank lines, whole-line comments, and
`NAME=value` entries with optional single or double quotes around values. They are parsed as data: shell commands and
variable interpolation are not evaluated.

## Bootstrap cache location

By default, when downloading the Kotlin Toolchain distribution, the wrapper script places it in a cache directory that is suitable
for the current OS:

| OS                                   | Cache directory                             |
|--------------------------------------|---------------------------------------------|
| :material-apple: macOS               | `$HOME/Library/Caches/JetBrains/Kotlin/cli` |
| :material-linux: Linux               | `$HOME/.cache/JetBrains/Kotlin/cli`[^2]     |
| :material-microsoft-windows: Windows | `%LOCALAPPDATA%\JetBrains\Kotlin\cli`       |

[^2]: The XDG convention is not supported at the moment for the bootstrap cache. 
      It is, however, respected for the regular Kotlin cache.

This location can be customized by setting the `KOTLIN_CLI_BOOTSTRAP_CACHE_DIR` environment variable.

## Disabling the welcome banner

When the wrapper script downloads a distribution for the first time, it displays a welcome message to the user.
This might be too much output if you're running Kotlin CLI in a CI environment, and provisioning the distribution on every
build.

You can disable the welcome banner by setting the `KOTLIN_CLI_NO_WELCOME_BANNER` environment variable to a non-empty value.
For instance, `KOTLIN_CLI_NO_WELCOME_BANNER=1`.

## Uncharted customization territories

!!! danger "Use at your own risk"

    While the Kotlin CLI currently is a JVM application, this may change in the future and all the functionality below will break
    without notice (for instance, we could publish the Kotlin CLI as a GraalVM native image).

    Moreover, using these customization features is generally not recommended and may break the Kotlin Toolchain in unexpected ways, 
    including the Kotlin Toolchain update mechanism.

### Customizing the Kotlin CLI's own JVM options

To add JVM options to the JVM running the Kotlin CLI, use the `KOTLIN_CLI_JAVA_OPTIONS` environment variable.

### Customizing the JRE used to run the Kotlin CLI

The Kotlin CLI runtime is not provisioned if the `KOTLIN_CLI_JAVA_HOME` environment variable is already provided.
Customizing this variable prevents the auto-provisioning of a JRE by the Kotlin CLI, but it puts the responsibility on you
to provide a valid JRE. The requirements for the JRE are subject to change without notice and are not documented at the
moment.

You can look inside the wrapper scripts to see which JRE is provisioned.

### Customizing the Kotlin Toolchain distribution URL

The Kotlin Toolchain distribution is downloaded from a Maven repository by fetching the following URL:
```
$KOTLIN_CLI_DOWNLOAD_ROOT/org/jetbrains/kotlin/kotlin-cli/${version}/kotlin-cli-${version}-dist.tgz
```
By default, `$KOTLIN_CLI_DOWNLOAD_ROOT` is `https://packages.jetbrains.team/maven/p/amper/amper`.
Changing this variable allows you to use your own Maven repository to host the Kotlin Toolchain distribution.

This is, again, not recommended — please use with care.
