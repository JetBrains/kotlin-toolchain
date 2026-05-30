---
description: |
  In this section, we'll cover all the puzzle pieces of a successful library publication to Maven Central or other 
  Maven repositories: PGP signing, sources/javadoc JARs, Central Publish portal credentials, and more.
---
# Publishing libraries

!!! info "The publishing feature is in preview, and is likely to change. Don't hesitate to share your feedback!"

!!! warning "Multiplatform library publication is not supported yet"

    At the moment, only JVM libraries can be published.
    While the `publish` command for KMP libraries will not complain, the KMP publications are for now incomplete and
    not consumable by other projects.

Library modules inside your project are useful for modularization, but you can take it one step further by publishing
your libraries so they can be used by other projects.

With a little bit of configuration, you'll be able to publish using the `kotlin publish` command.

## Publishing to a regular Maven repository

To publish to a Maven repository, you essentially need 3 things:

* the publication configuration
* the target repository
* the credentials to publish

This is how your `module.yaml` should look like:

```yaml title="module.yaml"
product: jvm/lib

repositories:
  - id: someIdOfYourChoosing #(1)!
    url: https://maven.pkg.github.com/my-org/my-repo #(2)!
    publish: true #(3)!
    credentials:
      file: creds.properties # a properties file containing your credentials 
      usernameKey: username # not the username, but the name of the property containing it
      passwordKey: password # not the password, but the name of the property containing it

settings:
  publishing:
    enabled: true
    group: org.example # enter your own groupID here
    version: 1.0.0
    # artifactId is optional, and defaults to your module's name
```

1. This is the ID you will use in the `publish` command when you want to publish to this repository:
   ```
   kotlin publish <id>
   ```

2. The URL of your custom repository

3. Enables publishing to this repository via the `kotlin publish <repoId>` command

You must then also create a `creds.properties` file (or whichever name you chose above), with two properties for the
username and password to use for publishing:

```
username=adele
password=imRollingInTheDeep
```

Once everything is set up, use the following command to publish all modules that enabled publishing (and that have 
configured a repository with ID `someIdOfYourChoosing`):

```
kotlin publish someIdOfYourChoosing
```

!!! note "Don't forget to publish your dependencies"

    If your module depends on other local modules, you must enable publishing for these other modules too.
    We recommend using a template to share the publishing configuration across all your published modules.

## Publishing to Maven Central

Maven Central is the most popular Maven repository. Most OSS libraries are published there.
Publishing your library to Maven Central makes it easier for your users to consume it.

### Prerequisites

To publish to Maven Central, you need to have:

* a Central Portal [account](https://central.sonatype.org/register/central-portal/)
* a Central Portal [namespace](https://central.sonatype.org/register/namespace/)
* a Central Portal [user token](https://central.sonatype.org/publish/generate-portal-token/) - remember the username and password parts.

### Configuration

There are a handful of requirements[^1] imposed by Sonatype to publish to Maven Central:

[^1]: You can learn more about them [on the official website](https://central.sonatype.org/publish/requirements/)

* javadocs and sources JARs must be published
* checksums for all artifacts must be published
* artifacts need to be signed with a PGP signature
* some mandatory metadata about the module must be present

You can satisfy all of these requirements with a little bit of configuration:

```yaml
product: jvm/lib

description: A meaningful description for this specific module

settings:
  publishing:
    enabled: true
    group: com.example #(1)!
    version: 1.0.0
    # artifactId is optional, and defaults to your module's name
    mavenCentral: enabled
    signArtifacts: true
    publishSources: true
    pom:
      url: https://example.com
      scm: https://github.com/my-org/example.git #(2)!
      developers:
        - name: Joffrey Bion
      licenses:
        - name: MIT
          url: https://opensource.org/license/mit
```

1. The `group` should correspond to the `groupId` of your Maven Central
   [namespace](https://central.sonatype.org/register/namespace/)
2. This is a shorthand for `pom.scm.url`.
   The `pom.scm.connection` and `pom.scm.developerConnection` are automatically derived from it using the value `scm:git:$url`.
   If this default doesn't work for you, you can set these properties explicitly to any value.

!!! note "Empty JavaDoc jar"

    At the moment, an empty JavaDoc JAR is added to the publication by default. We will soon add more control over this.

### Passing credentials

In order to publish using `kotlin publish mavenCentral`, you'll need to provide credentials in the form of environment
variables (usually done on the CI):

| Variable                                  | Description                                                                                                                                                            |
|-------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_USERNAME` | The `username` part of your Central Portal [user token](https://central.sonatype.org/publish/generate-portal-token/).                                                  |
| `KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_PASSWORD` | The `password` part of your Central Portal [user token](https://central.sonatype.org/publish/generate-portal-token/).                                                  |
| `KOTLIN_TOOLCHAIN_SIGNING_KEY`            | The ASCII-armored PGP signing key to use to sign artifacts. You can export a private key in this format using the `gpg --export-secret-keys --armor <KEY_ID>` command. |
| `KOTLIN_TOOLCHAIN_SIGNING_KEY_PASSPHRASE` | (optional) The passphrase to unlock the PGP key, if there is one.                                                                                                      |

!!! question "How do I get a PGP signing key?"

    If you have never needed a PGP signing key before, it might not be obvious to get started.
    Follow the instructions to [create and prepare a key](https://central.sonatype.org/publish/requirements/gpg) on the 
    Maven Central website.

### Publishing command

Use the following command to publish all modules that have `mavenCentral` publication enabled:

```
kotlin publish mavenCentral
```

This is usually done in a CI job.

### Publishing mode

The Kotlin Toolchain provides 2 modes for publishing:

* `manual` (default): in this mode, the `kotlin publish` command uploads the bundle, then waits for its validation, and
   then stops for manual verification. Once you're ready, you can use the
   [Central Portal UI](https://central.sonatype.com/publishing/deployments) to manually publish (release) your 
   artifacts.
* `auto`: the `kotlin publish` command does everything automatically from end to finish. This is useful when you want 
   to fully automate your publications.  

By default, the Kotlin Toolchain uses the `manual` mode, to avoid surprises.
Once the first deployment is successful, you might want to streamline the publication by switching to `auto` mode.
This can be done using `settings.mavenCentral.publishingMode: auto`.

!!! warning "One does not simply remove artifacts from Maven Central"

    When using the `auto` mode, you will not get a chance to look at the published artifacts before they are released to
    the public and set in stone in Maven Central. If there is a mistake in your publication, you will not be able to 
    remove artifacts from Maven Central, because Sonatype has a strict policy about this.
