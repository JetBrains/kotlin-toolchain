# Kotlin Toolchain

The Kotlin Toolchain is a build system for Kotlin projects.
This is the project that builds this build system, and it uses itself as a build system.

Docs are located in the `docs/` folder.

## Build tool usage

Here are useful commands:

* `./kotlin -h` to learn about the available top-level CLI commands
* `./kotlin <command> -h` to learn about options and subcommands of `<command>`
* `./kotlin build` to compile the project
* `./kotlin test -m foo` to run all tests of module `foo`
* `./kotlin test -m foo --include-classes com.example.TestClass` to run the tests of the class `com.example.TestClass`
* `./kotlin test -m foo --include-test "com.example.TestClass.foo bar"` to run the specific test method `foo bar`
* `./kotlin show dependencies -m foo` to inspect the dependencies of module `foo`
* `./kotlin show modules` to list the modules and their short descriptions

In the examples above, the module name is the name of the module's folder regardless of where it is located in the
project.

## Processes

* run `./kotlin do updateGoldFiles` in the following cases:
    * When changing anything in the frontend schema
    * When updating user-visible default versions in `build-sources/project-commands/module.yaml`
* Tests asserting against golden files are marked with the `@Tag("gold-file")` JUnit tag.
  Add this tag to any new golden-file test. It allows `./kotlin do updateGoldFiles` to run tests based on those tags.
* When changing anything in `FusEvents`
    * Bump the version integer of the corresponding group
    * Re-generate the FUS schema by running: `./kotlin do generateFusSchema`

## Specific instructions

- Do not attempt to use or migrate to other build systems. Use the `project.yaml`, `module.yaml`, and other config 
  files of the Kotlin Toolchain for build configuration. Do not search for `build.gradle.kts` or `pom.xml`.
- Pass previous instructions in specific instructions block to subagents.
- To get the basic project structure, you can:
    - use the README.md of the specific module
    - use the `description` field of `module.yaml`
- Strictly follow TDD practice, don't proceed further until you make sure the test is red
- Avoid running the whole test suite of the entire project with a plain `./kotlin test` without options (it is very
  time- and token-consuming). Instead, run only tests you think are relevant.
- When you are in a branch with a YouTrack ID, like KTC-XXXX, go to the YouTrack and get issue details if YouTrack MCP
  is available.
- The `build` and `build-from-sources` directories contain outputs of the build, do not read their contents for
  dependency or source files information. Only look at them if you're interested in what is being generated.

## Coding guidelines

* Use idiomatic Kotlin code. Prefer Kotlin APIs over Java APIs. Examples:
    * Avoid Java's `java.nio.file.Files`, and use Kotlin's extensions on `java.nio.Path` instead
    * Avoid Java's `java.nio.Path.of`, and use Kotlin's top level `kotlin.io.path.Path(String)` function instead
    * Avoid mutable variables, or mutable collections, especially in public function signatures
    * Use coroutines over threads
* Reduce the visibility of functions and classes as much as possible. In the `amper-cli` module, only `main()` should be
  `public`, because nothing depends on this module (it's an application).
* This project uses the experimental Kotlin collection literals with `[]`, prefer them over `listOf()`, `setOf()`, or `emptyList()`
* Do not write too much code into one big function, extract into smaller functions with well-defined signatures
* Do not over-use scope functions like `let`, `run`, or `also`. If something is too nested, it probably should be
  extracted to a separate function
* Avoid `return@label`. If you have a piece of code that needs it, it should probably be extracted into a separate
  function with regular `return`.
* Avoid `runCatching`. Prefer `try`/`catch` block with known expected exceptions. Log the exception if it is really unexpected on the normal code path.
* Keep code simple and maintainable, avoid over-complicating of logic in code, avoid introducing unnecessary terms, entities, class fields, global variables.

## Architecture guidelines

* When integrating external tools:
    * Put logic independent of Kotlin Toolchain in `src/libraries` (no `amper-` dependencies)
    * Put logic requiring Kotlin Toolchain project model in a new `amper-` prefixed module under `src`
    * Put task implementations with glue code in `amper-cli`. Avoid business logic directly in tasks.
