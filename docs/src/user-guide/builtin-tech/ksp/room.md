# Room

[Room](https://developer.android.com/training/data-storage/room) is the persistence library that provides an abstraction
layer over SQLite allowing your applications to store relevant data locally.

Its components include a runtime library and a KSP processor that should be configured in your module file.

## Configuring Room

You can use [a library catalog](../../../user-guide/dependencies.md#library-catalogs) to simplify further declaration of dependencies in module files:

```toml title="libs.versions.toml"
[versions]
room3 = "3.0.3"
sqlite = "2.7.1"

[libraries]
androidx-room3-runtime = { module = "androidx.room3:room3-runtime", version.ref = "room3" }
androidx-room3-compiler = { module = "androidx.room3:room3-compiler", version.ref = "room3" }
androidx-sqlite-bundled = { module = "androidx.sqlite:sqlite-bundled", version.ref = "sqlite" }
```

After that, add the runtime dependency and the KSP processor to the module file:

```yaml title="module.yaml"
product:
  type: kmp/lib
  platforms: [ android, iosArm64, iosSimulatorArm64, jvm ]
  
dependencies:
  - $libs.androidx.room3.runtime
  - $libs.androidx.sqlite.bundled

settings:
  kotlin:
    ksp:
      processors:
        - $libs.androidx.room3.compiler
```

### Export schemas

Room exports the database schema as a JSON file. It is recommended to store those files in your version control system.
To specify the schema directory, you need to pass the `room.schemaLocation` argument to the KSP processor:

```yaml
settings:
  kotlin:
    ksp:
      processors:
        - $libs.androidx.room3.compiler
      processorOptions:
        room.schemaLocation: ./schemas
```

!!! warning "Separating schema locations"

    In multiplatform modules, KSP is called for each platform separately. This means that several compilations can write
    to the JSON file concurrently, possibly corrupting it. At the moment, you have to separate schema location for each
    of the target platforms via using the `@platform` qualifiers:

    ```yaml title="module.yaml"
        product:
            type: kmp/lib
            platforms: [ android, iosArm64, iosSimulatorArm64, jvm ]
        
        # ... 
        
        settings@android:
          kotlin:
            ksp:
              processorOptions:
                room.schemaLocation: ./schemas@android
        
        settings@iosArm64:
          kotlin:
            ksp:
              processorOptions:
                room.schemaLocation: ./schemas@iosArm64
        
        settings@iosSimulatorArm64:
          kotlin:
            ksp:
              processorOptions:
                room.schemaLocation: ./schemas@iosSimulatorArm64
        
        settings@jvm:
          kotlin:
            ksp:
              processorOptions:
                room.schemaLocation: ./schemas@jvm
    ```

    Note that this is required even if your schemas are shared between all platforms. 
    This limitation comes from the Kotlin compilation model and how KSP aligns with it. Please follow 
    [the relevant KSP issue](https://github.com/google/ksp/issues/567) for more information.

    In the meantime, we'll search for the possible solution to remove this boilerplate. You can follow
    [the releavent issue in our issue tracker](https://youtrack.jetbrains.com/issue/KTC-6081).

## Additional resources

!!! example "Example: [Room with KMP]({{ examples_base_url }}/compose-multiplatform-room)"

For the latest information on Room, please refer to the [official documentation](https://developer.android.com/training/data-storage/room)
 and the [KMP Room guide](https://developer.android.com/kotlin/multiplatform/room#defining-database).