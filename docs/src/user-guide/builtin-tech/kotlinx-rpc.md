---
description: |
  kotlinx.rpc is a Kotlin Multiplatform library providing tools to implement Remote Procedure Calls (RPC) easily. 
  The Kotlin Toolchain provides support for it out of the box.
---
# :jetbrains-kotlinx-rpc: Kotlinx RPC

The [kotlinx.rpc](https://kotlin.github.io/kotlinx-rpc/get-started.html) library allows you to implement Remote 
Procedure Calls (RPC) more easily by generating boilerplate code behind the scenes for you.

To enable kotlinx.rpc support, add the following to the `module.yaml` file of each client or server module, and each 
module declaring `@Rpc` services:

```yaml
settings:
  kotlin:
    rpc: enabled
```

This will automatically:

* enable code generation for your `@Rpc` services via the kotlinx.rpc compiler plugin
* add the required `org.jetbrains.kotlinx:kotlinx-rpc-core` runtime dependency (implicitly)
* apply the kotlinx.rpc [BOM (Bill of Materials)](../dependencies.md#using-a-maven-bom) to align the versions of the 
  RPC-related artifacts
* add some useful [library catalog](../dependencies.md#library-catalogs) entries starting with `$kotlin.rpc.`
