# Kotlin Toolchain

The Kotlin Toolchain is a build system for Kotlin projects.
This is the project that builds this build system, and it uses itself as a build system.

Docs are located in the `docs/` folder.

## Usage

Here are useful commands:

* To learn about the available CLI commands: `./kotlin --help`
* To learn about the specific options of some command: `./kotlin <command> --help` (Example: `./kotlin build --help`)
* To compile the project: `./kotlin build`
* To run all tests of a given module: `./kotlin test -m <module_name>`
* To run a specific test class: `./kotlin test -m <module_name> --include-class <test_class_fqn>`, where <test_class_fqn> is the
  fully-qualified name of the test class.
* To run a specific test: `./kotlin test -m <module_name> --include-test <test_method_fqn>`, where `<test_method_fqn>` is the 
  fully-qualified name of the test method (don't forget to quote it if it contains spaces)
* To inspect the dependencies of a module: `./kotlin show dependencies -m <module_name>`
* To inspect the settings of a module: `./kotlin show settings -m <module_name>`

In the examples above, `<module_name>` is the name of the module's folder regardless of where it is located in the 
project.

You can get information about all available commands using `./kotlin -h` or help about the options and subcommands of each command using `./kotlin <command_name> -h`.

## Processes

When changing anything in the frontend schema, update golden test files by running: `./kotlin do updateGoldFiles`

## Specific instructions

- Never try searching `build.gradle.kts` or `pom.xml` for build configuration information.
- Never try searching for Gradle/Maven/another build system's files unless asked explicitly. The Kotlin Toolchain builds and manages its own build.
- Never try converting to other build systems, strictly use the `project.yaml`, `module.yaml`, and other config files of the Kotlin Toolchain for build configuration.
- Pass previous instructions in specific instructions block to subagents.
- Never use these tools: `Grep`, `Glob`, `Read`, `Edit`, `Write`, `Task(Explore)`. ALWAYS use JetBrains MCP equivalents instead. However, NEVER use `execute_terminal_command` tool. ALWAYS use default `Bash` instead.
- To get the basic project structure, you can do the following:
      - use README.md of the specific module
      - use `description` field of `module.yaml`
      - use `./kotlin show modules` or more specific with `--fields=name,type,platforms,description` for additional info
- Strictly follow TDD practice, don't proceed further until you make sure the test is red
- Avoid running the whole test suite of the entire project with a plain `./kotlin test` without options
  (it is very time- and token-consuming). Instead, run only tests you think are relevant.
- When you are in a branch with the YouTrack ID, like AMPER-XXXX, go to the YouTrack and get issue details if YouTrack MCP is available.
- The `build` and `build-from-sources` directories contain outputs of the build, usually not a sources to look at. 
  Only look at them if you're interested in what is being generated, not to look at dependencies or sources.
