# AOT training project

This is the workload used to train a JVM AOT cache ([JEP 483](https://openjdk.org/jeps/483)) for the Kotlin CLI.

## How it is used

To perform the training for a given Kotlin Toolchain version, first generate wrappers in the correct version:

```
kotlin update --create --target-dir=build-sources/aot-training-project --target-version=<version>
```

Then run the `trainAotCache` custom command:

```
kotlin do trainAotCache
```

This builds the project once, changes one line of a source file, and runs `kotlin build` again with 
`-XX:AOTCacheOutput=`. That second, incremental build is the training run: the JVM records which classes were loaded and 
which methods were hot, and assembles the cache when the CLI exits.

Use `kotlin do uploadAotCache` to compress it and publish it as a Maven artifact that depends on the current host machine.

## Why this project

The choice is backed by previous measurements done on our example projects: 42 caches trained on 6 projects with 7 
training commands, each measured on the 6 example projects for 5 workloads: CLI startup, `show modules`, up-to-date build,
incremental build, clean build). The main lessons:

1. **The training run must compile.** About 90% of the classes loaded during a build belong to the Kotlin compiler and
   its plugins, and they are only archived if the training run compiles something. Caches trained with `--version` or
   `show` commands are 3x smaller but save only 7-12% on clean builds, versus 24-27% for caches trained on a build.
   Startup, `show modules` and up-to-date builds are covered by any training run (53-64% saved).
2. **Bigger caches cost startup time.** The JVM eagerly loads every archived class, so the ~0.35 s startup with a
   160 MB cache becomes ~0.42 s with a 220 MB one. Caches trained on Android or multiplatform builds archive the
   Gradle tooling client (~3000 classes) and lose 3 points overall for it, while helping only Android projects.
3. **Otherwise, the training project barely matters** (less than 1 point between the JVM-only example projects), but
   domain-specific classes do help their domain: a cache that saw the Compose compiler plugin saves a few more
   points on Compose projects, and one that saw the Spring compiler plugins on Spring projects.

This project therefore stays small and JVM-only, but exercises the compiler paths that most projects hit:

| module | what it covers |
|---|---|
| `core` (jvm/lib) | a multi-file library with data classes, value classes, sealed hierarchies, generics, inline functions, lambdas, coroutines and Flow, plus **test sources** (test compilation is a separate compiler invocation with its own classpath); its coroutines dependency is `exported` |
| `desktop-app` (jvm/app) | depends on `core`, **Compose** enabled: the Compose compiler plugin (~1000 classes) and Compose resources tasks |
| `spring-service` (jvm/app) | depends on `core`, **Spring Boot** enabled: the `all-open` and `no-arg` compiler plugins, the Spring BOM, Java annotation processor resolution |

Having several modules with dependencies between them also trains the task graph, per-module dependency resolution
and inter-module classpath snapshotting, which single-module examples don't exercise.

## Measured results

Averaged time gain over the 6 benchmark projects (time saved vs without cache), using geo-mean:

| training run on this project                                             | cache size | startup | `show modules` | up-to-date build | incremental build | clean build | overall    |
|--------------------------------------------------------------------------|------------|---------|----------------|------------------|-------------------|-------------|------------|
| incremental `build` (our current choice)                               | 157 MB     | -54%    | -61%           | -61%             | -37%              | -22%        | **-49.0%** |
| clean `build`                                                            | 185 MB     | -51%    | -59%           | -60%             | -37%              | -26%        | -48.1%     |
| reference: best cache of the full matrix (`examples/jvm`, clean `build`) | 161 MB     | -53%    | -60%           | -61%             | -36%              | -26%        | -48.7%     |

The differences between these three are within measurement noise (about 1 point); the incremental training run was
chosen because it produces the smallest cache for the same result. On absolute terms, on the machine used for the
measurements, CLI startup goes from 0.75s to 0.34s, an up-to-date build from 1.6s to 0.6s, and an incremental
build of a small project from 6s to 3.5s.

Full gains on example projects when training with incremental build:

| example project         | kotlin --version    | kotlin show modules  | kotlin build (clean)    |
|-------------------------|---------------------|----------------------|-------------------------|
| compose-android         | -52% (765 → 365 ms) | -61% (1388 → 528 ms) | -11% (19266 → 17029 ms) |
| compose-desktop         | -54% (756 → 347 ms) | -61% (1372 → 532 ms) | -25% (9177 → 6828 ms)   |
| compose-multiplatform   | -52% (748 → 356 ms) | -60% (1407 → 552 ms) | -15% (18546 → 15746 ms) |
| jvm                     | -52% (753 → 354 ms) | -60% (1362 → 535 ms) | -33% (7785 → 5186 ms)   |
| ktor-simplest-sample    | -53% (764 → 355 ms) | -60% (1355 → 536 ms) | -32% (7787 → 5235 ms)   |
| spring-petclinic-kotlin | -53% (754 → 353 ms) | -58% (1397 → 584 ms) | -20% (15236 → 12053 ms) |
| spring-petclinic        | -53% (756 → 354 ms) | -58% (1415 → 585 ms) | -17% (7513 → 6181 ms)   |

## Maintaining it

- Keep it small: every extra dependency or plugin adds archived classes, which cost startup time on every run for
  every user. Only add something if it is on the hot path of a large share of projects.
- It must build on all platforms the CLI supports (Linux, macOS, Windows).
