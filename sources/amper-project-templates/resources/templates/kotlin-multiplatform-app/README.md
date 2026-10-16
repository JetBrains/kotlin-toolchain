# Kotlin Multiplatform project

This project is targeting Android, Desktop (JVM), iOS, Web, Server, built with
the [Kotlin Toolchain](https://kotlin-toolchain.org/latest/).

- [/{{APP_MODULE_DIR}}{{ANDROID_APP_MODULE_NAME}}](./{{APP_MODULE_DIR}}{{ANDROID_APP_MODULE_NAME}}) contains the Android application.
- [/{{APP_MODULE_DIR}}{{DESKTOP_APP_MODULE_NAME}}](./{{APP_MODULE_DIR}}{{DESKTOP_APP_MODULE_NAME}}) contains the desktop (JVM) application.
- [/{{APP_MODULE_DIR}}{{IOS_APP_MODULE_NAME}}](./{{APP_MODULE_DIR}}{{IOS_APP_MODULE_NAME}}) contains the iOS application.
- [/{{APP_MODULE_DIR}}{{SHARED_MODULE_NAME}}](./{{APP_MODULE_DIR}}{{SHARED_MODULE_NAME}}) holds the code shared across your applications — Compose UI,
  business logic, and platform-specific implementations. [src](./{{APP_MODULE_DIR}}{{SHARED_MODULE_NAME}}/src) is for common code; the
  sibling `src@<platform>` folders (for example `src@android`) hold code compiled only for the platform named in the
  folder.
- [/{{APP_MODULE_DIR}}{{WEB_APP_MODULE_NAME}}](./{{APP_MODULE_DIR}}{{WEB_APP_MODULE_NAME}}) contains the web application, compiled to WebAssembly with Kotlin/Wasm.
- [/{{CORE_MODULE_NAME}}](./{{CORE_MODULE_NAME}}) holds the code shared across the project.
- [/{{SERVER_MODULE_NAME}}](./{{SERVER_MODULE_NAME}}) contains the Ktor server application.

The `kotlin` (macOS/Linux) and `kotlin.bat` (Windows) scripts in the project root are self-bootstrapping wrappers for
the Kotlin Toolchain: they download the pinned toolchain version on first use, so no separate installation is required.
Build the whole project with `./kotlin build`.

### Running

Run each module from the command line:

- Android app: `./kotlin run -m {{ANDROID_APP_MODULE_NAME}}`
- Desktop app: `./kotlin run -m {{DESKTOP_APP_MODULE_NAME}}`
- iOS app: `./kotlin run -m {{IOS_APP_MODULE_NAME}}`
- Web app: `./kotlin run -m {{WEB_APP_MODULE_NAME}}`
- Server: `./kotlin run -m {{SERVER_MODULE_NAME}}`

### Testing

Run the project's tests with `./kotlin test`, or `./kotlin test -m <module>` for a single module.

---

Learn more
about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html), [Kotlin Toolchain](https://kotlin-toolchain.org/latest/), [Compose Multiplatform](https://kotlinlang.org/compose-multiplatform/), [Kotlin/Wasm](https://kotl.in/wasm/), [Ktor](https://ktor.io/).
