---
hide:
  - navigation
  - toc
---

<div class="hero" markdown>

<h1>The Kotlin Toolchain</h1>

<p class="tagline">
A unified entry point into Kotlin. 
Build JVM, Android, iOS, multiplatform, and server-side
applications with a simple declarative configuration.
</p>

<div class="install-grid" markdown>

<div class="install-card" markdown>

### :octicons-terminal-16: Command Line

<div class="method-label">via SDKMAN</div>

```shell
sdk install kotlintoolchain
```

<div class="method-label">or via installer script</div>

=== ":material-apple: macOS / :material-linux: Linux"

    ```shell
    curl -fsSL https://kotl.in/install.sh | sh
    ```

=== ":material-microsoft-windows: Windows"

    ```powershell
    powershell -ExecutionPolicy ByPass -c "irm 'https://kotl.in/install.ps1' | iex"
    ```

<div class="card-footer" markdown>
[:octicons-arrow-right-16: CLI documentation](cli/index.md)
</div>

</div>

<div class="install-card" markdown>

### :intellij-jetbrains: IntelliJ IDEA

Install the [Kotlin Toolchain plugin](https://plugins.jetbrains.com/plugin/31850-kotlin-toolchain/) in IntelliJ IDEA 2026.1.2+

**File → New → Project → Kotlin**

<img src="images/ij-new-kotlin-project-light.png#only-light" width="100%" alt="New Kotlin project in IntelliJ IDEA">
<img src="images/ij-new-kotlin-project-dark.png#only-dark" width="100%" alt="New Kotlin project in IntelliJ IDEA">

</div>

</div>

<div class="nav-buttons" markdown>

[Get started](getting-started/index.md){ .md-button }
[:material-book-open-page-variant: User Guide](user-guide/index.md){ .md-button }
[:material-github: Examples]({{ examples_base_url }}){ .md-button }

</div>
</div>

<div class="example-section" markdown>

## Minimal Configuration

=== "JVM Application"

    ```yaml title="module.yaml"
    product: jvm/app
    ```

    That's it. Toolchains, test framework, and everything you need — configured automatically.

=== "Compose Multiplatform"

    === ":material-apple: iOS"

        ```yaml title="ios-app/module.yaml"
        product: ios/app

        dependencies:
          - ../shared

        settings:
          compose: enabled
        ```

    === ":material-android: Android"

        ```yaml title="android-app/module.yaml"
        product: android/app

        dependencies:
          - ../shared

        settings:
          compose: enabled
        ```

    === ":material-laptop: Desktop"

        ```yaml title="desktop-app/module.yaml"
        product: jvm/app

        dependencies:
          - ../shared

        settings:
          compose: enabled
        ```

    ```yaml title="shared/module.yaml"
    # Produce a shared library for the JVM, Android, and iOS platforms:
    product:
      type: kmp/lib
      platforms: [jvm, android, iosArm64, iosSimulatorArm64, iosX64]

    # Shared Compose dependencies:
    dependencies:
      - $compose.foundation: exported
      - $compose.material3: exported

    # Android-only dependencies
    dependencies@android:
      # Android-specific integration with Compose
      - androidx.activity:activity-compose:1.7.2: exported
      - androidx.appcompat:appcompat:1.6.1: exported

    settings:
      # Enable Kotlin serialization
      kotlin:
        serialization: json

      # Enable the Compose Multiplatform framework
      compose: enabled
    ```

    Shared UI across Android, iOS, and desktop with a single codebase.

[:octicons-arrow-right-16: See more examples]({{ examples_base_url }}){ .md-button }

</div>

<div class="status-section" markdown>

The Kotlin Toolchain is [Alpha](https://kotlinlang.org/docs/components-stability.html#stability-levels-explained). We'd love your feedback!

[:jetbrains-youtrack: Report an issue](https://youtrack.jetbrains.com/newIssue?project=AMPER){ .md-button }
[:material-slack: Join Slack](https://kotlinlang.slack.com/archives/C062WG3A7T8){ .md-button }

</div>

<div class="platforms-section" markdown>

:material-android: Android
· :material-apple: iOS
· :material-laptop: Desktop
· :material-server: Server
· :jetbrains-kotlin-multiplatform: Multiplatform
· :jetbrains-compose-multiplatform: Compose

</div>
