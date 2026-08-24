---
description: Learn how to use the `wasm-wasi/app` product types in a module to build WebAssembly applications that run using WASI.
---
# :simple-webassembly: Kotlin/Wasm WASI application

Use the `wasm-wasi/app` product type in a module to build a WebAssembly application that can run using [WASI](https://wasi.dev/) using the 
[Kotlin/Wasm](https://kotlinlang.org/docs/wasm-overview.html) technology.
These applications can be run using runtimes like [Node.js](https://nodejs.org/en), [Deno](https://deno.com/), [WasmEdge](https://wasmedge.org/), and others.

!!! warning "Incomplete preview"

    The support for the Wasm-WASI target is currently in an incomplete preview state.

    For example, running a WASI application is not supported out of the box at the moment like other application 
    types, and needs some manual work (see the [Running WASI application](#running-wasi-application)).

    We're eager to hear more about your use cases and how we can improve this experience!
    Please let us know in a [:jetbrains-youtrack: YouTrack](https://youtrack.jetbrains.com/issues/KTC) issue, or in
    our [:material-slack: Slack channel](https://kotlinlang.slack.com/archives/C062WG3A7T8).

## Module layout

Here is an overview of the module layout for a Kotlin/Wasm application:

```shell
my-module/
├─ src/
│  ├─ main.kt
│  ╰─ Util.kt
├─ test/
│  ╰─ UtilTest.kt
╰─ module.yaml
```

## Entry point

The entry point of a Kotlin/Wasm application is a top-level `main` function in the `src` folder.

Multiple `main` functions are not supported. If you have multiple main functions, the one chosen by the compiler as 
an entry point is unspecified.

## Packaging

Using the `build` command compiles your code to WebAssembly (`.wasm` file) and generates a JavaScript wrapper file 
(`.mjs`) to load it.

These files are produced in the `artifacts/CompiledWebArtifact/<module-name>wasmWasi<debug|release>` folder 
at the moment, but this is subject to change.

There are no extra packaging facilities at the moment, and the `package` command is not supported for this product type.

## Running WASI application

!!! warning "Kotlin/Wasm application targeting WASI cannot be run directly by the Kotlin CLI at the moment."

To run WASI application, you need to:

1. Install a runtime that supports WebAssembly (e.g., Node.js, Deno, WasmEdge, ...).
2. Build your module with `./kotlin build`
3. Using your runtime, run the `.mjs` wrapper file that calls the `.wasm` code produced by your module.
   See the [Packaging](#packaging) section above to know where this file is located.