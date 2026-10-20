/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.executablejar

import org.jetbrains.amper.jar.CompressionStrategy.Selective
import org.jetbrains.amper.jar.JarConfig
import org.jetbrains.amper.jar.ZipConfig
import org.jetbrains.amper.jar.ZipInput
import org.jetbrains.amper.jar.writeJar
import org.jetbrains.amper.stdlib.hashing.sha256String
import org.jetbrains.amper.stdlib.io.path.withTempDir
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.io.path.extension
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.writeText

private object ArchivePaths {
    /**
     * The base directory for application classes inside the JAR.
     * The default is "BOOT-INF/classes/".
     */
    val classesDirectory: Path = Path("BOOT-INF/classes/")

    /**
     * The base directory for dependencies inside the JAR.
     * The default is "BOOT-INF/lib/".
     */
    val libDirectory: Path = Path("BOOT-INF/lib/")

    /**
     * The path for the classpath index file.
     * Default is "BOOT-INF/classpath.idx".
     */
    val classpathIndexPath: Path = Path("BOOT-INF/classpath.idx")

    /**
     * The path for the layers index file.
     * The default is "BOOT-INF/layers.idx".
     */
    val layersIndexPath: Path = Path("BOOT-INF/layers.idx")
}

/**
 * Writes an executable JAR file in the Spring Boot format.
 *
 * See [the format specification](https://docs.spring.io/spring-boot/specification/executable-jar/index.html) for
 * details.
 */
// TODO make runtimeDependencies layers customizable
// TODO return the created layers so it can be used to build docker images
fun Path.writeExecutableJar(
    compiledClasses: List<Path>,
    runtimeDependencies: List<Path>,
    springBootLoaderJarUnpacked: Path,
    mainClass: String?,
) {
    withTempDir { tempDir ->
        val inputs = prepareJarInputs(
            classesRoots = compiledClasses,
            runtimeDependencies = runtimeDependencies,
            springBootLoaderJar = springBootLoaderJarUnpacked,
            tempDir = tempDir,
        )
        writeJar(inputs, config = createJarConfig(mainClass, loaderMainClass(springBootLoaderJarUnpacked)))
    }
}

private fun prepareJarInputs(
    classesRoots: List<Path>,
    runtimeDependencies: List<Path>,
    springBootLoaderJar: Path,
    tempDir: Path,
): List<ZipInput> {
    val libInputs = runtimeDependencies.asDeduplicatedLibInputs()

    return buildList {
        add(ZipInput(path = springBootLoaderJar, destPathInArchive = Path(".")))

        addAll(libInputs)

        add(ZipInput(createClasspathIndex(tempDir, libInputs), ArchivePaths.classpathIndexPath))
        add(ZipInput(createLayersIndex(tempDir), ArchivePaths.layersIndexPath))

        classesRoots.forEach { classesRoot ->
            // All class roots are merged under the same classes directory in the archive
            add(ZipInput(path = classesRoot, destPathInArchive = ArchivePaths.classesDirectory))
        }
    }
}

/**
 * Returns [ZipInput]s corresponding to these [Path]s, with destinations in [ArchivePaths.libDirectory].
 *
 * Inputs with identical filenames are allowed, and their destination is a unique name based on the hash of their
 * contents.
 *
 * ### Design note
 *
 * It is possible that some code accesses its containing JAR name, and renaming JARs does break such code.
 * That said, this shouldn't be expected to work when bundling everything into a single JAR anyway, so we consider
 * this behavior acceptable. Also, frankly, there isn't really an alternative - the users can't do anything about the
 * names of the JARs of their dependencies.
 */
private fun List<Path>.asDeduplicatedLibInputs(): List<ZipInput> {
    val collidingJarNames = groupingBy { it.name }
        .eachCount()
        .filterValues { it > 1 }
        .keys
    val renamedDestinations = mutableMapOf<String, Path>()
    return mapNotNull { jar ->
        val destFilename = if (jar.name in collidingJarNames) {
            jar.fileNameWithHashSuffix().also { newName ->
                // colliding files with identical names but ALSO identical contents
                val collidingIdenticalJar = renamedDestinations.put(newName, jar)
                require(collidingIdenticalJar == null) {
                    """
                        Cannot build executable JAR with dependencies that have the same name and contents:
                          $jar
                          $collidingIdenticalJar
                        Two JARs with identical names are allowed, but not if they have identical contents.
                    """.trimIndent()
                }
            }
        } else {
            jar.name
        }
        ZipInput(path = jar, destPathInArchive = ArchivePaths.libDirectory / destFilename)
    }
}

/**
 * Returns this Path's [name][Path.name] with a hash of the file contents added before the extension.
 *
 * Example:
 * ```kotlin
 * Path("dir/foo.jar").fileNameWithHashSuffix() == "foo-80b6d614.jar"
 * ```
 */
private fun Path.fileNameWithHashSuffix(): String {
    val sha = sha256String().take(8)
    val ext = extension
    return if (ext.isEmpty()) "$nameWithoutExtension-$sha" else "${nameWithoutExtension}-$sha.$ext"
}

private fun createJarConfig(mainClassName: String?, loaderMainClass: String): JarConfig = JarConfig(
    mainClassFqn = loaderMainClass,
    manifestProperties = createManifestProperties(mainClassName),
    zipConfig = ZipConfig(
        // The format requires STORE mode for nested jars
        // See https://docs.spring.io/spring-boot/specification/executable-jar/restrictions.html
        compressionStrategy = Selective(["^BOOT-INF/lib/.+"]),
    ),
)

/**
 * Finds the Spring Boot loader main class that serves as entrypoint to load nested jars and run the real main class.
 */
private fun loaderMainClass(springBootLoaderJarUnpacked: Path): String = when {
    // Spring Boot 3 and 4
    (springBootLoaderJarUnpacked / "org/springframework/boot/loader/launch/JarLauncher.class").exists() ->
        "org.springframework.boot.loader.launch.JarLauncher"
    // Spring Boot 2
    (springBootLoaderJarUnpacked / "org/springframework/boot/loader/JarLauncher.class").exists() ->
        "org.springframework.boot.loader.JarLauncher"
    else -> error("Could not find a valid Spring Boot loader main class in $springBootLoaderJarUnpacked")
}

/**
 * Returns the default manifest properties required for Spring Boot executable JAR.
 */
private fun createManifestProperties(mainClassName: String?): Map<String, String> = buildMap {
    put("Spring-Boot-Classes", ArchivePaths.classesDirectory.invariantSeparatorsPathString)
    put("Spring-Boot-Lib", ArchivePaths.libDirectory.invariantSeparatorsPathString)
    put("Spring-Boot-Classpath-Index", ArchivePaths.classpathIndexPath.invariantSeparatorsPathString)
    put("Spring-Boot-Layers-Index", ArchivePaths.layersIndexPath.invariantSeparatorsPathString)
    if (mainClassName != null) {
        put("Start-Class", mainClassName)
    }
}

/**
 * Creates a classpath index file as per the
 * [executable JAR specification](https://docs.spring.io/spring-boot/specification/executable-jar/nested-jars.html#appendix.executable-jar.nested-jars.classpath-index).
 */
private fun createClasspathIndex(tempDir: Path, libInputs: List<ZipInput>): Path {
    val jarPathsFromArchiveRoot = libInputs.map { it.destPathInArchive.invariantSeparatorsPathString }
    val classpathIndexFile = tempDir.resolve("classpath.idx")
    classpathIndexFile.writeText(jarPathsFromArchiveRoot.joinToString(separator = "") { "- \"$it\"\n" })
    return classpathIndexFile
}

private fun createLayersIndex(tempDir: Path): Path {
    val layersFile = tempDir.resolve("layers.idx")
    // TODO optimize layers here: dependencies (lib/) should be split into 3 layers with explicit lists of JARs:
    //   - regular remote dependencies
    //   - SNAPSHOT remote dependencies
    //   - local dependencies
    layersFile.writeText("""
        - "spring-boot-loader":
          - "org/"
        - "dependencies":
          - "${ArchivePaths.libDirectory.invariantSeparatorsPathString}"
        - "application":
          - "${ArchivePaths.classesDirectory.invariantSeparatorsPathString}"
          - "${ArchivePaths.classpathIndexPath.invariantSeparatorsPathString}"
          - "${ArchivePaths.layersIndexPath.invariantSeparatorsPathString}"
          - "META-INF/"
    """.trimIndent())
    return layersFile
}
