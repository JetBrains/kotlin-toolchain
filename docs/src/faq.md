---
description: Frequently asked questions about the Kotlin Toolchain.
hide:
  - navigation
---
## General

### Is the Kotlin Toolchain a brand-new build tool from JetBrains?

The Kotlin Toolchain is a unified entry point into Kotlin with a focus on user experience and IDE support. It includes build tooling
functionality as one of its core components.

### Do you plan to support only Kotlin?

The Kotlin Toolchain already supports both Kotlin and Java as first-class citizens.
Also, because one of the Kotlin Toolchain's main targets is Kotlin Multiplatform projects, it supports Swift and Objective-C for iOS.

We’ll investigate other tech stacks in the future based on the demand for them.

### Which target platforms are supported?

Currently, you can create applications for the JVM, Android, iOS, macOS, Linux, Windows, but also JS and WASM (although
those cannot be run directly with the Kotlin CLI).

Libraries can be created for all Kotlin Multiplatform targets.

### Does the Kotlin Toolchain support Compose Multiplatform?

Yes, you can configure Compose for Android, iOS, and desktop.
Check out our [Compose Multiplatform guide](user-guide/builtin-tech/compose-multiplatform.md).

### Does the Kotlin Toolchain support Kotlin/JS or Kotlin/Wasm projects?

Yes, but it doesn't provide tooling to work on full stack web projects yet. For instance, the Kotlin Toolchain doesn't install any 
browser or Node.js runtime, doesn't generate or process any HTML entry point, and cannot run `js/app` modules on its own.

### What functionality do you plan to support?

We plan to cover all the common use cases based on demand.
At the moment, we’re working on extensibility, publication, and exploring Maven migration and integration.

### Will the Kotlin Toolchain be open source?

The Kotlin Toolchain is already open source. Check out our [GitHub repository](https://github.com/JetBrains/kotlin-toolchain) to see what we're 
up to!

### When will the Kotlin Toolchain be released as stable?

Right now, we’re focusing on getting feedback and understanding your needs. Based on that, we’ll be able to provide a
more accurate estimate of a release date sometime in the future.

### Should I start my next project with the Kotlin Toolchain?

You’re welcome to use it in any type of project. However, please understand that the Kotlin Toolchain is still in the alpha
phase, and we expect some things to change.

### Should I migrate my existing projects?

Understanding real-world scenarios is crucial for us to provide a better experience, so from our side we’d love
to hear about the challenges you may face porting existing projects. However, please understand that the project is
still in the experimental phase, and we cannot guarantee that all scenarios can be supported.

### How do I report a bug?

Please report problems to our [:jetbrains-youtrack: YouTrack issue tracker](https://youtrack.jetbrains.com/issues/KTC).
Since this project is in the experimental phase, we would also greatly appreciate feedback and suggestions regarding 
the configuration experience – join our 
[:material-slack: Slack channel](https://kotlinlang.slack.com/archives/C062WG3A7T8) for discussion.

### Why don’t you use Kotlin for the Kotlin Toolchain's configuration files?

Currently, we use YAML as a simple and readable markup language. It allows us to experiment with the UX and the IDE
support much faster. We’ll review the language choice as we proceed with the design and based on demand. The Kotlin DSL,
or a limited form thereof, is one of the possible options.

Having said that, we believe that the declarative approach to project configuration has significant advantages over the
imperative approach. Declarative configuration is easily toolable, recovery from errors is much easier, and
interpretation is much faster. These properties are critical for a good UX.

Our final language choice will be made based on the overall UX it provides.

### Why not simply improve Gradle?

We believe there is room to improve the project configuration experience and IDE support.
With the Kotlin Toolchain, we want to show you our design and get your feedback, as it will help us to decide which direction to take
the design.

At the same time, we are also [working with the Gradle team](https://blog.gradle.org/declarative-gradle) to improve
Gradle support in our IDEs and Gradle itself.

### What about Gradle extensibility and plugins?

We aim to support most of the Kotlin and Kotlin Multiplatform use cases out of the box and offer a reasonable level of
extensibility.

## Usage

### What are the requirements to use the Kotlin Toolchain?

The Kotlin CLI doesn't require any software preinstallation, except the Xcode toolchain if you want to 
build iOS applications. See the [CLI instructions](cli/index.md).

We recommend using the latest [IntelliJ IDEA EAP](https://www.jetbrains.com/idea/nextversion/) to make the most out of
the Kotlin Toolchain. Our focus on the tooling and UX really pays off in IntelliJ IDEA. To learn about the required and optional 
plugins in IntelliJ IDEA, see the [IDE setup instructions](getting-started/ide-setup.md).

### How do I create a new Kotlin project?

You have several options:

* Open IntelliJ IDEA and create a new Kotlin project with the Kotlin Toolchain

* Kick-start your project using one of the [examples]({{ examples_base_url }})

* Download Kotlin CLI by following the [CLI instructions](cli/index.md), and generate a project 
  from a template using the `./kotlin init` command.

### How do I create a multi-module project in the Kotlin Toolchain?

See the documentation on the [project layout](user-guide/basics.md#project-layout).

### Is there an automated migration tool?

Yes, for Maven projects: the `./kotlin tool convert-project` command does the bulk of the conversion for you, on a
best-effort basis. See the [Migrating from Maven](getting-started/migrating-from-maven.md) guide for details.

For Gradle projects, there is no conversion tool at the moment. Gradle build scripts contain arbitrary code, which makes
it hard to define a proper automatic and deterministic conversion. However, AI agents are quite good at this, especially
with the [Kotlin Toolchain skills](https://github.com/singleton11/kotlin-toolchain-skills).

### Feature X is not yet supported, what can I do?

Please let us know about it! We're eager to hear what you're trying to do, because we plan to expand the list of
supported use cases based on demand.
Please submit your requests and suggestions in the
[:jetbrains-youtrack: YouTrack issue tracker](https://youtrack.jetbrains.com/issues/KTC) or join the
[:material-slack: Slack channel](https://kotlinlang.slack.com/archives/C062WG3A7T8) for discussions.

### Can I write a custom task or use a plugin?

Yes! The Kotlin Toolchain now includes a preview of a plugin system. See the dedicated [docs](user-guide/plugins/overview.md).
