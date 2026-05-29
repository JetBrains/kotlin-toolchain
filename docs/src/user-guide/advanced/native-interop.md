---
description: |
  Learn how to use C and Objective-C libraries in your Kotlin Multiplatform modules using cinterop in the Kotlin Toolchain.
---
# Native interoperability (cinterop)

Kotlin/Native provides the `cinterop` tool to facilitate interaction with C and Objective-C libraries. 
The Kotlin Toolchain supports `cinterop` out of the box with zero configuration for the common use case.

## Basic usage

To add a C or Objective-C interop to your multiplatform module:

1. Create a `cinterop` directory in your module root (alongside `src` and `test`).
2. Drop your [`.def` files](https://kotlinlang.org/docs/native-definition-file.html) into this directory.

The Kotlin Toolchain will automatically detect these files and configure the `cinterop` tool for all applicable native platforms. 
**No additional configuration in your `module.yaml` is required**.

## Platform-specific definitions

You can use the [`@<platform>` qualifier](../multiplatform.md#platform-qualifier)
for the `cinterop` directory to limit interop definitions to specific platforms or platform families.

!!! info "At the file level vs. directory level"
    The `.def` file format already supports platform-specific configuration for the library, for example:
    `compilerOpts.linux` vs. `compilerOpts.osx`.

    So it is possible to use a single `.def` file in the common `cinterop` directory 
    instead of having separate platform-specific files under, e.g., `cinterop@linux` and `cinterop@macos`.
    Both approaches are currently valid - use the one you prefer.

## Advanced usage

If you need the `.def` file generated or provisioned (for example, to implement custom library location or provisioning logic),
you can write a [plugin](../plugins/overview.md) for this.
See the [relevant docs](../plugins/topics/tasks.md#contributing-back-to-the-build) for more details on how to contribute `cinteropDefinitions`.
