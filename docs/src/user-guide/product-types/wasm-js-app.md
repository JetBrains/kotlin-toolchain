---
description: Learn how to use the `wasm-js/app` product type in a module to build WebAssembly applications that run in browser.
---
# :simple-webassembly: Kotlin/Wasm web application

Use the `wasm-js/app` product type in a module to build a WebAssembly application that can run in browsers using the 
[Kotlin/Wasm](https://kotlinlang.org/docs/wasm-overview.html) technology.

## Module layout

Here is an overview of the module layout for a Kotlin/Wasm application:

```shell
my-module/
├─ resources # (1)!
│  ╰─ index.html # (2)!
├─ src/
│  ├─ main.kt
│  ╰─ Util.kt
├─ test/
│  ╰─ UtilTest.kt
╰─ module.yaml
```

1. Resources placed here are packaged together with the resulting application
2. `index.html` is the entrypoint for your web application. It is optional to include it—see [index.html configuration](#indexhtml-configuration) for more details

## Entry point

The entry point of a Kotlin/Wasm application is a top-level `main` function in the `src` folder.

Multiple `main` functions are not supported. If you have multiple main functions, the one chosen by the compiler as 
an entry point is unspecified.

### `index.html` configuration

By default, the Kotlin Toolchain provides a minimal `index.html` for the application.
It is the entry point that the browser should load to open the application.
If you want to customize it (e.g., to add an analytics script or CSS), you can put your own version of it 
under the `resources` folder of the module.
There are several template variables that are available for use in the `index.html`:

- `{% raw %}{{kotlin.moduleName}}{% endraw %}` — the name of the module
- `{% raw %}{{kotlin.moduleFile}}{% endraw %}` — the name of the `mjs` wrapper that loads your Wasm application
- `{% raw %}{{kotlin.scripts}}{% endraw %}` — the minimal required set of scripts to properly load your application. 
  Includes `{% raw %}{{kotlin.moduleFile}}{% endraw %}` and import map loader for loading third-party dependencies. 

The default `index.html` looks like this:

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <style> /*(1)!*/
        html, body {
            width: 100%;
            height: 100%;
            margin: 0;
            padding: 0;
            overflow: hidden;
        }
    </style>
    <title>{% raw %}{{kotlin.moduleName}}{% endraw %}</title>
    {% raw %}{{kotlin.scripts}}{% endraw %}
</head>
<body>

</body>
</html>
```

1. Styles required for the content to fill the entire screen. See [Compose Multiplatform documentation](https://kotlinlang.org/docs/multiplatform/compose-css-styles.html) for explanation.

## Dependencies

Currently, defining direct NPM dependencies for your application is not supported. However, if you use a Kotlin Multiplatform library
that requires such a dependency (e.g., `@js-joda/core` for `kotlinx-datetime`), the dependency will be downloaded and packed together
with your application.

## Packaging

Using the `build` command packages your application under the `build/tasks_<module-name>_buildWasmJsAppWasmJs<Debug|Release>` 
folder, but this is subject to change.

The package includes:

- a `<module-name>.wasm` file with your app module's code
- a set of `.mjs` files to load it
- all required JS dependencies
- the Skiko Wasm runtime
- the `index.html` page that serves as the entrypoint of the application

There are no extra packaging facilities at the moment, and the `package` command is not supported for this product type.

## Testing

Tests targeting Wasm JS target are not supported yet, but we are [working on it](https://youtrack.jetbrains.com/issue/KTC-5576).

## Running Wasm application in your browser

You can use the `run` command to start the local server and open your application in the browser.