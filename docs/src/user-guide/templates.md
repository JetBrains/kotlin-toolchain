---
description: This page describes how to use templates to share configuration between modules in the project.
---
# Templates

In modularized projects, there is often a need to have a certain common configuration for all or some modules.
Typical examples could be a testing framework used in all modules or a Kotlin language version.

The Kotlin Toolchain offers a way to extract whole sections or their parts into reusable template files. 
These files are named `<name>.module-template.yaml` and have the same structure as `module.yaml` files.

A template is applied to a `module.yaml` file by listing relative path to it in the `apply:` section:

```yaml title="module.yaml"
product: jvm/app

apply: 
  - ../common.module-template.yaml
```

```yaml title="../common.module-template.yaml"
test-dependencies:
  - org.jetbrains.kotlin:kotlin-test:1.8.10

settings:
  kotlin:
    languageVersion: 1.8
```

Sections in the template can also have `@platform`-qualifiers.

!!! note

    Template files can't have the `product:` section.

Templates are applied one by one, using the same rules as 
[platform-specific dependencies and settings](multiplatform.md#dependencysettings-propagation):

- Scalar values (strings, numbers etc.) are overridden.
- Mappings and lists are appended.

Settings and dependencies from the `module.yaml` file are applied last. The position of the `apply:` section doesn't 
matter, the `module.yaml` file content always has precedence. E.g.:

```yaml title="common.module-template.yaml"
dependencies:
  - ../shared

settings:
  kotlin:
    languageVersion: 1.8
  compose: enabled
```

```yaml title="module.yaml"
product: jvm/app

apply:
  - ./common.module-template.yaml

dependencies:
  - ../jvm-util

settings:
  kotlin:
    languageVersion: 1.9
  jvm:
    release: 8
```

After applying the template, the resulting effective module is:

```yaml title="module.yaml"
product: jvm/app

dependencies:  # lists appended
  - ../shared
  - ../jvm-util

settings:  # objects merged
  kotlin:
    languageVersion: 1.9  # module.yaml overwrites value
  compose: enabled        # from the template
  jvm:
    release: 8   # from the module.yaml
```

## Nested templates

It is possible to apply templates to other templates by using the same `apply` section in the template files:

```yaml title="java.module-template.yaml"
settings:
  jvm:
    release: 11
```

```yaml title="spring.module-template.yaml"
apply:
  - ./java.module-template.yaml

settings:
  springBoot: enabled
```

```yaml title="module.yaml"
product: jvm/app

apply:
  - ./spring.module-template.yaml
```

The resulting effective module is:

```yaml title="module.yaml"
product: jvm/app

settings:
  jvm:
    release: 11
  springBoot: enabled
```

Templates follow the same precedence rules as regular modules, so in the example above values from 
`spring.module-template.yaml` will override values from `java.module-template.yaml`.

Each template is applied to the resulting module only once even if it is applied in multiple templates used in a module. E.g.:

```yaml title="common.module-template.yaml"
dependencies:
  - ./core-lib
```

```yaml title="client.module-template.yaml"
apply:
  - ./common.module-template.yaml

dependencies:
  - ./client-lib
```

```yaml title="server.module-template.yaml"
apply:
  - ./common.module-template.yaml

dependencies:
  - ./server-lib
```

```yaml title="module.yaml"
product: jvm/app

apply:
  - ./client.module-template.yaml
  - ./server.module-template.yaml
```

will result in the effective module:

```yaml title="module.yaml"
product: jvm/app

dependencies:
  - ./core-lib   # core-lib is added to the list only once
  - ./client-lib
  - ./server-lib
```

## Conflict resolution

If two templates define different scalar values for the same property and neither template is more specific in 
the `apply` graph, the Kotlin Toolchain reports a conflict. 

```yaml title="java17-compatible.module-template.yaml"
settings:
  jvm:
    release: 17
```

```yaml title="java21-compatible.module-template.yaml"
settings:
  jvm:
    release: 21
```

With only `java17-compatible` and `java21-compatible`, `settings.jvm.release` is conflicting (`17` vs `21`) 
because these templates are siblings.

```yaml title="module.yaml"
product: jvm/app

apply:
  - ./java17-compatible.module-template.yaml
  - ./java21-compatible.module-template.yaml

# Error: Conflicting values for property `release`
```

You can solve the conflict by explicitly setting the property value in the module applying both templates:

```yaml title="module.yaml"
product: jvm/app

apply:
  - ./java17-compatible.module-template.yaml
  - ./java21-compatible.module-template.yaml

settings:
  jvm:
    release: 21 #(1)!
```

1. The explicitly set value `21` takes precedence over conflicting values,
   and no conflict is reported

If you still want to keep the setting as a template, you can resolve it by introducing a template that applies both 
conflicting templates __and__ defines the final value.

```yaml title="java-runtime-policy.module-template.yaml"
apply:
  - ./java17-compatible.module-template.yaml
  - ./java21-compatible.module-template.yaml

settings:
  jvm:
    release: 21
```

```yaml title="module.yaml"
product: jvm/app

apply:
  - ./java-runtime-policy.module-template.yaml #(1)!
```

1. The value of `jvm.release` coming from `java-runtime-policy` template takes precedence over conflicting values
   from `java17` and `java21` templates, and no conflict is reported
