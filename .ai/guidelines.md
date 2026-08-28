# Kotlin Toolchain

The Kotlin Toolchain is a build system for Kotlin projects.
This is the project that builds this build system, and it uses itself as a build system.

Docs are located in the `docs/` folder.

## Build tool usage

Here are useful commands:

* To learn about the available CLI commands: `./kotlin --help`
* To learn about the specific options of some command: `./kotlin <command> --help` (Example: `./kotlin build --help`)
* To compile the project: `./kotlin build`
* To run all tests of a given module: `./kotlin test -m <module_name>`
* To run a specific test class: `./kotlin test -m <module_name> --include-class <test_class_fqn>`, where 
  `<test_class_fqn>` is the fully-qualified name of the test class.
* To run a specific test: `./kotlin test -m <module_name> --include-test <test_method_fqn>`, where `<test_method_fqn>`
  is the fully-qualified name of the test method (don't forget to quote it if it contains spaces)
* To inspect the dependencies of a module: `./kotlin show dependencies -m <module_name>`
* To inspect the settings of a module: `./kotlin show settings -m <module_name>`

In the examples above, `<module_name>` is the name of the module's folder regardless of where it is located in the
project.

## Processes

* When changing anything in the frontend schema, update golden test files by running: `./kotlin do updateGoldFiles`
* When updating user-visible default versions, do it in `build-sources/project-commands/module.yaml`, and run
  `./kotlin do updateGoldFiles`

## Specific instructions

- Never try searching `build.gradle.kts` or `pom.xml` for build configuration information.
- Never try searching for Gradle/Maven/another build system's files unless asked explicitly. The Kotlin Toolchain builds
  and manages its own build.
- Never try converting to other build systems, strictly use the `project.yaml`, `module.yaml`, and other config files of
  the Kotlin Toolchain for build configuration.
- Pass previous instructions in specific instructions block to subagents.
- To get the basic project structure, you can do the following:
  - use README.md of the specific module
  - use `description` field of `module.yaml`
  - use `./kotlin show modules` or more specific with `--fields=name,type,platforms,description` for additional info
- Strictly follow TDD practice, don't proceed further until you make sure the test is red
- Avoid running the whole test suite of the entire project with a plain `./kotlin test` without options (it is very
  time- and token-consuming). Instead, run only tests you think are relevant.
- When you are in a branch with the YouTrack ID, like KTC-XXXX, go to the YouTrack and get issue details if YouTrack MCP
  is available.
- The `build` and `build-from-sources` directories contain outputs of the build, usually not interesting sources to look
  at.
  Only look at them if you're interested in what is being generated, not to look at dependencies or sources.

## Coding guidelines

* Use idiomatic Kotlin code. Prefer Kotlin APIs over Java APIs. Examples:
    * Avoid Java's `java.nio.file.Files`, and use Kotlin's extensions on `java.nio.Path` instead
    * Avoid mutable variables, or mutable collections, especially in public function signatures
    * Use coroutines over threads
* Reduce the visibility of functions and classes as much as possible. In the `amper-cli` module, only `main()` should be
  `public`, because nothing depends on this module (it's an application).
* This project uses the experimental Kotlin collection literals with `[]`, prefer them over `listOf()` or `emptyList()`
* Do not write too much code into one big function, extract into smaller functions with well-defined signatures
* Do not over-use scope functions like `let`, `run`, or `also`. If something is too nested, it probably should be
  extracted to a separate function
* Avoid `return@label`. If you have a piece of code that needs it, it should probably be extracted into a separate
  function with regular `return`.

## Architecture guidelines

* When integrating with external tools put all logic/business rules that are not specific to the Kotlin Toolchain into
  a new module under `src/libraries`, with no dependencies on Kotlin Toolchain-specific modules (prefixed `amper-`).
  If there is also some logic that requires knowledge of the Kotlin Toolchain project model, create a new `amper-`
  prefixed module under `src` for it.
