---
description: Custom commands are tasks that perform side effects extending the build capabilities.
---

# Custom commands

Custom commands are tasks that perform side effects extending the build capabilities. E.g., such tasks can print a report to the console,
update the linter baseline, upload artifacts to a custom repository, etc.

## Running custom commands

To run a command, one can use the `do` command followed by the command name.

```shell
$ ./kotlin do updateDetektBaseline
```

!!! note
    To get the name of the commands available in the project run `./kotlin show commands`.

It is also possible to run commands only for some modules by passing the module name in the `--module` option (can be repeated):

```shell
$ ./kotlin do updateDetektBaseline --module api --module core  # Will update baseline only for the 'api' and 'core' modules
# or
$ ./kotlin do updateDetektBaseline -m api -m core
```

## Create custom commands

To create a custom command, you need to [write a task](tasks.md) and register it as a command. 

To register a task as a custom command, add it to the `commands` section of the `plugin.yaml`:

```yaml title="plugin.yaml"
tasks:
  updateBaseline:
    action: !runDetektForBaseline
      sources: ${module.kotlinJavaSources}
      outputFile: ${module.rootDir}/detekt/baseline.xml

commands:
  - updateBaseline  # Can be called via `./kotlin do updateBaseline`

  # You can customize the name of the command using the full form
  - name: updateDetektBaseline  # Can be called via `./kotlin do updateDetektBaseline`
    performedBy: updateBaseline
```