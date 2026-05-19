---
description: The Kotlin CLI is a command-line tool to build, run, test, and package your project without an IDE.
---
# :octicons-terminal-16: Kotlin CLI

The Kotlin CLI is a command-line tool to build, run, test, and package your project without an IDE.
It is useful both locally and in CI/CD pipelines.

## Installation

To use the Kotlin CLI, you need to download the [Kotlin wrapper script](provisioning.md/#whats-the-wrapper-script).

It is recommended to place it in your project root and check it into your VCS, so your team can build and run your 
project without any installation, no matter their OS.

??? success "IntelliJ IDEA can take care of this for you"

    New projects created using the IntelliJ IDEA wizard will already contain the wrapper scripts. Also, if you create a
    `module.yaml` file in a blank project, IntelliJ IDEA will offer to setup the wrapper scripts for you.

Use the following command in your project directory to download the script and set up the Kotlin Toolchain:

--8<-- "includes/cli-install.md"

The `./kotlin update -c` command following the download is not strictly necessary, but it will automatically get the 
wrapper script for the other OS. It is good practice to check them both into your VCS so your team can build and run 
your project without any installation, on any OS.

!!! note

    The first time you run the Kotlin wrapper script, it will take some time to download the Kotlin CLI distribution.
    Subsequent runs will be faster, as the downloaded files will be cached locally.

    The `./kotlin update` call that is part of the above installation command will actually do this first run for you.

## Exploring Kotlin CLI commands

The root `./kotlin` command and all subcommands support the `-h` (or `--help`) option to explore what is possible:

```shell
./kotlin --help       # shows the available commands and general options
./kotlin build --help # shows the options for the 'build' command specifically
```

Useful commands:

- `kotlin init` to create a new Kotlin project
- `kotlin build` to compile and link all code in the project
- `kotlin run` to run your application
- `kotlin test` to run tests in the project
- `kotlin show (modules|settings|dependencies|tasks)` to introspect the project's configuration
- `kotlin clean` to remove the project's build output and caches

!!! example "Try it out!"

    Create a new project using the `./kotlin init` command and select the *JVM console application* template.

    Then build and run the application using `./kotlin run`.


## Tab-completion

If you’re using `bash`, `zsh`, or `fish`, you can generate a completion script to source as part of your shell’s
configuration, to get tab completion for Kotlin CLI commands.

First, generate the completion script using the `generate-completion` command, specifying the shell you use:

=== "bash"

    ```shell
    ./kotlin generate-completion bash > ~/kotlin-completion.sh
    ```

=== "zsh"

    ```shell
    ./kotlin generate-completion zsh > ~/kotlin-completion.sh
    ```

=== "fish"

    ```shell
    ./kotlin generate-completion fish > ~/kotlin-completion.sh
    ```

Then load the script in your shell (this can be added to `.bashrc`, `.zshrc`, or similar configuration files to load it
automatically):

```shell
source ~/kotlin-completion.sh
```

You should now have tab completion available for Kotlin CLI subcommands, options, and option values.

## Updating the Kotlin Toolchain to a newer version

Run `./kotlin update` to update the Kotlin CLI scripts and the toolchain distribution to the latest released version.
Use the `--dev` option if you want to try the bleeding edge dev build of the Kotlin Toolchain (no guarantees are made on these builds).

See `./kotlin update -h` for more information about the available options.

!!! tip "Don't forget to regenerate your tab-completion script, if you have one."
