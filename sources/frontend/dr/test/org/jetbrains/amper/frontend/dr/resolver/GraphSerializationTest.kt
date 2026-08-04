/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.frontend.dr.resolver

import javassist.Modifier
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.amper.dependency.resolution.DependencyGraph
import org.jetbrains.amper.dependency.resolution.DependencyGraph.Companion.toGraph
import org.jetbrains.amper.dependency.resolution.DependencyNode
import org.jetbrains.amper.dependency.resolution.GraphJson
import org.jetbrains.amper.dependency.resolution.MavenDependencyConstraintNode
import org.jetbrains.amper.dependency.resolution.MavenDependencyNode
import org.jetbrains.amper.dependency.resolution.ResolutionScope
import org.jetbrains.amper.dependency.resolution.SerializableDependencyNode
import org.jetbrains.amper.dependency.resolution.diagnostics.Message
import org.jetbrains.amper.dependency.resolution.group
import org.jetbrains.amper.dependency.resolution.isOrphan
import org.jetbrains.amper.test.assertEqualsWithDiff
import org.jetbrains.amper.test.runTestWithMdc
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.reflections.Reflections
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.reflect.KClass
import kotlin.reflect.jvm.jvmName
import kotlin.test.assertTrue

class GraphSerializationTest: BaseModuleDrTest() {

    override val testGoldenFilesRoot: Path = super.testGoldenFilesRoot / "graphSerialization"

    val json = GraphJson.json

    @Test
    fun serializationTestJava(testInfo: TestInfo) = runSlowModuleDependenciesTest(checkIncrementalCache = false) {
        val aom = getTestProjectModel("jvm-transitive-dependencies", testDataRoot)

        val appModuleGraph = doTestByFile(
            testInfo,
            aom,
            resolutionInput = ideSyncTestResolutionInput,
            module = "D2",
            filter = ideSyncModuleResolutionFilter.copy(scope = ResolutionScope.COMPILE)
        )

        assertRepetitiveGraphSerialization(appModuleGraph, testInfo)
    }

    @Test
    fun serializationTestKmp(testInfo: TestInfo) = runSlowModuleDependenciesTest(checkIncrementalCache = false) {
        val aom = getTestProjectModel("compose-multiplatform", testDataRoot)

        val iosAppModuleDeps = doTestByFile(
            testInfo,
            aom,
            ideSyncTestResolutionInput.copy(
                resolutionSettings = ideSyncTestResolutionInput.resolutionSettings.copy(
                    includeNonExportedNative = false
                )
            ),
            module = "ios-app",
            filter = ideSyncModuleResolutionFilter.copy(scope = ResolutionScope.COMPILE),
            verifyMessages = false
        )

        assertRepetitiveGraphSerialization(iosAppModuleDeps, testInfo)
    }

    @Test
    fun serializationTestInvalidDependencies(testInfo: TestInfo) = runSlowModuleDependenciesTest(checkIncrementalCache = false) {
        val aom = getTestProjectModel("jvm-unresolved-dependencies", testDataRoot)

        // Run the test that calculates diagnostics for all invalid dependencies
        val root = doTestByFile(
            testInfo,
            aom,
            ideSyncTestResolutionInput,
            filter = ideSyncModuleResolutionFilter,
            verifyMessages = false
        )

        val deserializedRoot = assertRepetitiveGraphSerialization(root, testInfo)

        fun DependencyNode.getErrorMessagesForChild(group: String): Set<String> =
            distinctBfsSequence()
                .filterIsInstance<DirectFragmentDependencyNode>()
                .first { it.dependencyNode.group == group }
                .dependencyNode
                .messages.map { it.message }.toSet()

        val expectedDiagnostic = "Unable to resolve dependency com.jetbrains.intellij.platform:core:233.13763.1"

        kotlin.test.assertEquals(
            expectedDiagnostic,
            root.getErrorMessagesForChild("com.jetbrains.intellij.platform").single(),
            "Diagnostic messages taken from deserialized graph differs from original ones"
        )

        kotlin.test.assertEquals(
            expectedDiagnostic,
            deserializedRoot.getErrorMessagesForChild("com.jetbrains.intellij.platform").single(),
            "Diagnostic messages taken from deserialized graph differs from original ones"
        )
    }

    /**
     * @return deserialized graph
     */
    private suspend fun assertRepetitiveGraphSerialization(root: DependencyNode, testInfo: TestInfo): DependencyNode {
        val nodeDeserialized = assertGraphSerialization(root, testInfo)
        val nodeDeserializedTwice = assertGraphSerialization(nodeDeserialized, testInfo)
        return nodeDeserializedTwice
    }

    /**
     * @return deserialized graph
     */
    private suspend fun assertGraphSerialization(root: DependencyNode, testInfo: TestInfo): DependencyNode {
        val serializableGraph = root.toGraph()
        assertGraphStructure(testInfo, root, serializableGraph)

        val encoded = json.encodeToString(serializableGraph)
        val decoded = json.decodeFromString(DependencyGraph.serializer(), encoded)
        val encodedOfDecoded = json.encodeToString(decoded)

        kotlin.test.assertEquals(
            encoded,
            encodedOfDecoded,
            "decoded string being encoded again differs from initially encoded"
        )

        val nodeDeserialized = decoded.root
        assertEqualsWithDiff(
            expected = root.prettyPrint(),
            actual = nodeDeserialized.prettyPrint(),
            message = "decoded graph pretty print representation differs from the original graph",
        )
        return nodeDeserialized
    }

    private suspend fun assertGraphStructure(testInfo: TestInfo, root: DependencyNode, graph: DependencyGraph) {
        root.assertGraphStructure(testInfo, GraphType.ORIGINAL)
        graph.assertGraphStructure(testInfo)

        val encodedGraph = json.encodeToString(graph)
        val decodedGraph = json.decodeFromString(DependencyGraph.serializer(), encodedGraph)
        decodedGraph.assertGraphStructure(testInfo)

//        graph.assertNodePlainIndexes(testInfo)
    }

    private suspend fun DependencyGraph.assertGraphStructure(testInfo: TestInfo) {
        root.assertGraphStructure(testInfo, GraphType.DESERIALIZED)
    }

    private suspend fun DependencyNode.assertGraphStructure(testInfo: TestInfo, graphType: GraphType) {
        assertParentsInGraph(testInfo, graphType)
        // todo (AB) : This should be uncommented after fix of
        // todo (AB) : https://youtrack.jetbrains.com/issue/AMPER-4887.
        // todo (AB) : (when DR became stable enough and will produce the same set of overriddenBy for all subsequent runs)
        // assertOverriddenByInGraph(testInfo, graphType)
    }

    private suspend fun DependencyNode.assertParentsInGraph(testInfo: TestInfo, graphType: GraphType) {
        val filterOrphans = (graphType == GraphType.ORIGINAL)

        fun DependencyNode.nodeName() = if (this is ModuleDependencyNode) "module:$moduleName" else graphEntryName

        val actual = distinctBfsSequence().flatMap { dep ->
            dep.parents
                .filter { !filterOrphans || !it.isOrphan(this@assertParentsInGraph) }
                .map { it.nodeName() to dep.nodeName() }
        }.toSet()
            .sortedBy { it.first + it.second }
            .joinToString(System.lineSeparator())

        val goldenFile = goldenFileOsArchAware(
            "${testInfo.testMethod.get().name.replace(" ", "_")}.parents.txt")
        val expected = getGoldenFileText(goldenFile, fileDescription = "Golden file for dependency graph parents")
        withActualDumpAndDelayedAssertion(goldenFile) {
            assertEqualsWithDiff(
                expected = expected.lines(),
                actual = actual.lines(),
                message = "Unexpected parents entries in ${graphType.pretty} graph",
            )
        }
    }

    private suspend fun DependencyNode.assertOverriddenByInGraph(testInfo: TestInfo, graphType: GraphType) {
        val filterOrphans = (graphType == GraphType.ORIGINAL)

        val allOverriddenBy = buildList {
            distinctBfsSequence()
                .filter { it is MavenDependencyNode || it is MavenDependencyConstraintNode }
                .forEach { dep ->
                    when (dep) {
                        is MavenDependencyNode -> addAll(dep.overriddenBy.sortedBy { it.graphEntryName }
                            .filter{ !filterOrphans || !it.isOrphan(this@assertOverriddenByInGraph) }
                            .map { it to dep })
                        is MavenDependencyConstraintNode -> addAll(dep.overriddenBy.sortedBy { it.graphEntryName }
                            .filter{ !filterOrphans || !it.isOrphan(this@assertOverriddenByInGraph) }
                            .map { it to dep })
                        else -> error("unexpected node type ${dep::class.java.simpleName}")
                    }
                }
        }
        val actual = allOverriddenBy.joinToString(System.lineSeparator())

        val goldenFile = goldenFileOsArchAware(
            "${testInfo.testMethod.get().name.replace(" ", "_")}.overriddenBy.txt")

        val expected = getGoldenFileText(goldenFile, fileDescription = "Golden file for dependency graph overriddenBy entries")
        withActualDumpAndDelayedAssertion(goldenFile) {
            assertEqualsWithDiff(
                expected = expected.lines(),
                actual = actual.lines(),
                message = "Unexpected overriddenBy entries in ${graphType.pretty} graph",
            )
        }
    }

    private enum class GraphType(val pretty: String) {
        ORIGINAL ("original"),
        DESERIALIZED ("deserialized")
    }

    /**
     * This check is not applied since indexes are not stable from one execution to another since those depend on the dependency nodes order in the cache,
     * and it in turn depends on the order of both 'node.children' and 'node.overriddenBy' while the latter have no stable sort order
     * (and it is quite tricky to sort it since it depends not only on coordinates, but on resolution context as well)
     */
    private suspend fun DependencyGraph.assertNodePlainIndexes(testInfo: TestInfo) {
        val actual = graphContext.allDependencyNodes
            .map { "${it.value}:  ${it.key.graphEntryName} (${(it.key as? MavenDependencyNode)?.dependency?.resolutionConfig?.platforms?.joinToString(",") { it.pretty } }"}
            .joinToString(System.lineSeparator())

        val goldenFile = goldenFileOsArchAware("${testInfo.testMethod.get().name.replace(" ", "_")}.indexes.txt")
        val expected = getGoldenFileText(goldenFile, fileDescription = "Golden file for serialized dependency graph indexes")
        withActualDumpAndDelayedAssertion(goldenFile) {
            assertEqualsWithDiff(
                expected = expected.lines(),
                actual = actual.lines(),
                message = "graph indexes differs from expected ones",
            )
        }
    }

    /**
     * This check is not applied since indexes of dependencies in serialized graph JSON are not stable from one execution to another.
     */
    private suspend fun assertSerializedGraphByGoldenFile(testInfo: TestInfo, encoded: String) {
        val goldenFile = goldenFileOsArchAware("${testInfo.testMethod.get().name.replace(" ", "_")}.graph.txt")
        val expected = getGoldenFileText(goldenFile, fileDescription = "Golden file for serialized dependency graph")
        withActualDumpAndDelayedAssertion(goldenFile) {
            assertEqualsWithDiff(
                expected = expected.lines(),
                actual = encoded.lines(),
                message = "decoded graph pretty print representation differs from the original graph",
            )
        }
    }

    @Test
    fun `all classes implementing Message are annotated with Serializable and properly registered`() = runTestWithMdc {
        checkPolymorphicRequirements(Message::class)
    }

    @Test
    fun `all classes implementing DependencyNodePlain are annotated with Serializable and properly registered`() =
        runTestWithMdc {
            checkPolymorphicRequirements(SerializableDependencyNode::class)
        }

    /**
     * Check that all classes implementing the given interface are annotated with [Serializable] and properly registered
     */
    @OptIn(ExperimentalSerializationApi::class)
   inline fun <reified T: Any> checkPolymorphicRequirements(
        kClass: KClass<T>,
        excludingSubClasses: List<KClass<out T>>? = null,
    ) {
        assertTrue("An abstract class or interface is expected, but ${kClass.simpleName} was given") { kClass.isAbstract }

        val reflections = Reflections("org.jetbrains.amper")
        val allNonAbstractChildrenJava = reflections
            .getSubTypesOf(kClass.java)
            .filterNot { kClass ->
                kClass.isInterface
                        || Modifier.isAbstract(kClass.modifiers)
                        || (excludingSubClasses != null && excludingSubClasses.any { kClass.isAssignableFrom(it.java) })
            }

        val allNonAbstractChildrenKotlin = allNonAbstractChildrenJava.map { it.kotlin }

        val notSerializable =
            allNonAbstractChildrenKotlin.filter { it.annotations.none { it is Serializable } }.map { it.simpleName }.toSet()
        assertTrue("${kClass.simpleName} should has serializable children only, but the following are not: $notSerializable") {
            notSerializable.isEmpty()
        }

        val notRegistered = allNonAbstractChildrenKotlin.filter {
            val getSerializedClassName = it.annotations.filterIsInstance<SerialName>().singleOrNull()?.value ?: it.jvmName
            json.serializersModule.getPolymorphic(kClass, getSerializedClassName) == null
        }
        assertTrue("Serializable classes implementing ${kClass.simpleName}, should be registered in a polymorphic serializer module: $notRegistered") {
            notRegistered.isEmpty()
        }
    }

    /**
     * Plain serializable nodes are used as a key in Map the same way as their sourcing counterparts from the origin dependency graph.
     * Nodes with the same content might belong to different resolution context and should be left separated,
     * this is why data classes are not suitable here.
     */
    @Test
    fun `all classes implementing DependencyNodePlain are not data classes`() = runTestWithMdc {
        val kClass = SerializableDependencyNode::class

        val reflections = Reflections("org.jetbrains.amper")
        val allNonAbstractChildrenJava = reflections
            .getSubTypesOf(kClass.java)
            .filterNot { it.isInterface || Modifier.isAbstract(it::class.java.modifiers) }

        val allNonAbstractChildrenKotlin = allNonAbstractChildrenJava.map { it.kotlin }

        val dataClasses =
            allNonAbstractChildrenKotlin.filter { it.isData }.map { it.simpleName }.toSet()
        assertTrue("${kClass.simpleName} should has no data class children, but the following are not: $dataClasses") {
            dataClasses.isEmpty()
        }
    }
}