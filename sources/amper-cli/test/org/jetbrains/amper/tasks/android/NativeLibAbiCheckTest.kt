/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.android

import kotlin.test.Test
import kotlin.test.assertEquals

class NativeLibAbiCheckTest {

    @Test
    fun `no abi at all is consistent`() {
        assertEquals([], findIncompleteAbis(mapOf()))
    }

    @Test
    fun `a single abi is always consistent`() {
        val libs = mapOf("arm64-v8a" to setOf("liba.so", "libb.so"))
        assertEquals([], findIncompleteAbis(libs))
    }

    @Test
    fun `abis carrying the same libraries are consistent`() {
        val libs = mapOf(
            "arm64-v8a" to setOf("liba.so", "libb.so"),
            "x86_64" to setOf("libb.so", "liba.so"),
        )
        assertEquals([], findIncompleteAbis(libs))
    }

    @Test
    fun `an abi missing a library present in another abi is incomplete`() {
        val libs = mapOf(
            "arm64-v8a" to setOf("liba.so", "libb.so"),
            "x86_64" to setOf("liba.so"),
        )
        assertEquals([IncompleteAbi(abi = "x86_64", missingLibraryNames = setOf("libb.so"))], findIncompleteAbis(libs))
    }

    @Test
    fun `disjoint abis are both incomplete`() {
        val libs = mapOf(
            "arm64-v8a" to setOf("liba.so"),
            "x86_64" to setOf("libb.so"),
        )
        assertEquals(
            [
                IncompleteAbi(abi = "arm64-v8a", missingLibraryNames = setOf("libb.so")),
                IncompleteAbi(abi = "x86_64", missingLibraryNames = setOf("liba.so")),
            ],
            findIncompleteAbis(libs),
        )
    }

    @Test
    fun `incomplete abis are reported in a stable order`() {
        val libs = mapOf(
            "x86_64" to setOf("liba.so"),
            "arm64-v8a" to setOf("liba.so", "libb.so", "libc.so"),
            "armeabi-v7a" to setOf("liba.so"),
        )
        assertEquals(
            ["armeabi-v7a", "x86_64"],
            findIncompleteAbis(libs).map { it.abi },
        )
    }

    @Test
    fun `an abi missing several libraries lists all of them`() {
        val libs = mapOf(
            "arm64-v8a" to setOf("liba.so", "libb.so", "libc.so"),
            "x86_64" to setOf("liba.so"),
        )
        assertEquals(
            setOf("libb.so", "libc.so"),
            findIncompleteAbis(libs).single().missingLibraryNames,
        )
    }

    @Test
    fun `apk entries are grouped by abi`() {
        val entries = [
            "AndroidManifest.xml",
            "classes.dex",
            "lib/arm64-v8a/libtest.so",
            "lib/arm64-v8a/libother.so",
            "lib/x86_64/libtest.so",
            "res/layout/main.xml",
        ]
        assertEquals(
            mapOf(
                "arm64-v8a" to setOf("libtest.so", "libother.so"),
                "x86_64" to setOf("libtest.so"),
            ),
            nativeLibsByAbi(entries.asSequence()),
        )
    }

    @Test
    fun `bundle entries under the base module are grouped by abi`() {
        val entries = [
            "BundleConfig.pb",
            "base/manifest/AndroidManifest.xml",
            "base/lib/arm64-v8a/libtest.so",
            "base/lib/x86_64/libtest.so",
        ]
        assertEquals(
            mapOf(
                "arm64-v8a" to setOf("libtest.so"),
                "x86_64" to setOf("libtest.so"),
            ),
            nativeLibsByAbi(entries.asSequence()),
        )
    }

    @Test
    fun `non-so files under an abi directory are ignored`() {
        val entries = ["lib/arm64-v8a/libtest.so", "lib/arm64-v8a/README.txt"]
        assertEquals(mapOf("arm64-v8a" to setOf("libtest.so")), nativeLibsByAbi(entries.asSequence()))
    }
}
