---
description: Checks are tasks that validate your application or the code base and report any issues found.
---
# Checks

Checks are tasks that validate your application or the code base and report any issues found.

## Running checks

To run checks, one can use the `check` command. If no arguments are provided, the command will launch all the tests
(similar to the `test` command) and all the checks registered in the plugins.

To run only a subset of checks, pass them as arguments to the `check` command:

```shell
$ ./kotlin check detekt apiCheck
```

!!! note
    To get the name of the checks available in the project run `./kotlin show checks`.

If you want to run all the checks except some, you can use the `--skip` option:

```shell
$ ./kotlin check --skip tests  # Will run all the checks except the built-in 'tests' checker
```

It is also possible to run checks only for some modules by passing the module name in the `--module` option (can be repeated):

```shell
$ ./kotlin check --module api --module core  # Will only run checks in the 'api' and 'core' modules
# or
$ ./kotlin check -m api -m core
```

## Create checks

To create a check, you need to [write a task](tasks.md) and register it as a check. 

To register a task as a check, add it to the `checks` section of the `plugin.yaml`:

```yaml title="plugin.yaml"
tasks:
  lint:
    action: !kotlinJavaLint
      sources: ${module.kotlinJavaSources}

checks:
  - lint  # Can be called via `./kotlin check lint`

  # You can customize the name of the check using the full form
  - name: myLint  # Can be called via `./kotlin check myLint`
    performedBy: lint
```

!!! note
    To fail a check, the underlying task should throw an exception during the task execution.
