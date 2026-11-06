/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.ios

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosCliUtilsTest {

    @Test
    fun `pid is parsed from the simctl launch output`() {
        assertEquals(12345L, parseSimctlLaunchPid("org.jetbrains.example: 12345", bundleId = "org.jetbrains.example"))
    }

    @Test
    fun `pid is parsed from the simctl launch output with unrelated lines around`() {
        val output = """
            WARNING: something unrelated: 7
            org.jetbrains.example: 42
        """.trimIndent()
        assertEquals(42L, parseSimctlLaunchPid(output, bundleId = "org.jetbrains.example"))
    }

    @Test
    fun `no pid is parsed when the simctl launch output contains no pid line for the bundle id`() {
        assertNull(parseSimctlLaunchPid("", bundleId = "org.jetbrains.example"))
        assertNull(parseSimctlLaunchPid("some line without a pid", bundleId = "org.jetbrains.example"))
        assertNull(parseSimctlLaunchPid("WARNING: unrelated number: 7", bundleId = "org.jetbrains.example"))
        assertNull(parseSimctlLaunchPid("org.jetbrains.another: 42", bundleId = "org.jetbrains.example"))
    }

    @Test
    fun `pid is parsed from the devicectl launch output`() {
        val json = """
            {
              "info" : {
                "outcome" : {
                  "code" : 0,
                  "domain" : "com.apple.coredevicectl"
                }
              },
              "result" : {
                "process" : {
                  "processIdentifier" : 4212
                }
              }
            }
        """.trimIndent()
        assertEquals(4212L, parseDevicectlLaunchPid(json))
    }

    @Test
    fun `no pid is parsed from a malformed devicectl launch output`() {
        assertNull(parseDevicectlLaunchPid("not a JSON"))
        assertNull(parseDevicectlLaunchPid("""{ "result" : { } }"""))
    }

    @Test
    fun `process ids are parsed from the devicectl processes output`() {
        val json = """
            {
              "info" : {
                "outcome" : {
                  "code" : 0,
                  "domain" : "com.apple.coredevicectl"
                }
              },
              "result" : {
                "runningProcesses" : [
                  {
                    "processIdentifier" : 111,
                    "processName" : "app1"
                  },
                  {
                    "processName" : "app-without-a-pid"
                  },
                  {
                    "processIdentifier" : 222,
                    "processName" : "app2"
                  }
                ]
              }
            }
        """.trimIndent()
        assertEquals([111L, 222L], parseDevicectlRunningProcessIds(json))
    }

    @Test
    fun `no process ids are parsed from a malformed devicectl processes output`() {
        assertEquals([], parseDevicectlRunningProcessIds("not a JSON"))
        assertEquals([], parseDevicectlRunningProcessIds("""{ "result" : { } }"""))
    }
}
