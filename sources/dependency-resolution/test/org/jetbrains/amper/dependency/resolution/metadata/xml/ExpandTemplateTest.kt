/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution.metadata.xml

import kotlinx.coroutines.runBlocking
import org.jetbrains.amper.incrementalcache.getDynamicInputs
import org.junit.jupiter.api.extension.ExtendWith
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension
import uk.org.webcompere.systemstubs.properties.SystemProperties
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Maven substitutes references to system properties and environment variables anywhere in the pom, not only
 * in the profile activation conditions, and the properties declared in the pom take precedence over those,
 * see `org.apache.maven.model.interpolation.AbstractStringBasedModelInterpolator.createValueSources`.
 */
@ExtendWith(SystemStubsExtension::class)
class ExpandTemplateTest {

    @Test
    fun `a system property is substituted`(systemProperties: SystemProperties) {
        systemProperties.set("dr.test.natives", "natives-linux")

        assertEquals("natives-linux", expandTemplate("\${dr.test.natives}"))
    }

    @Test
    fun `a property declared in the pom takes precedence over a system property`(systemProperties: SystemProperties) {
        systemProperties.set("dr.test.natives", "natives-linux")

        assertEquals(
            "natives-windows",
            expandTemplate("\${dr.test.natives}", pomProperties = mapOf("dr.test.natives" to "natives-windows")),
        )
    }

    @Test
    fun `an environment variable is substituted via the 'env' prefix`(environmentVariables: EnvironmentVariables) {
        environmentVariables.set("DR_TEST_NATIVES", "natives-linux")

        assertEquals("natives-linux", expandTemplate("\${env.DR_TEST_NATIVES}"))
    }

    @Test
    fun `a reference that can't be resolved is kept as is`() {
        assertEquals("\${dr.test.undefined}", expandTemplate("\${dr.test.undefined}"))
    }

    @Test
    fun `a property declared in the pom is substituted`() {
        assertEquals(
            "natives-windows",
            expandTemplate("\${dr.test.natives}", pomProperties = mapOf("dr.test.natives" to "natives-windows")),
        )
    }

    private fun expandTemplate(
        template: String,
        pomProperties: Map<String, String> = emptyMap(),
    ): String = runBlocking {
        val project = Project(properties = Properties(pomProperties))
        template.expandTemplate(project, getDynamicInputs())
    }
}
