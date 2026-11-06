/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.client

import org.jetbrains.amper.test.runTestWithMdc
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class ProcessOwnedEventLogStorageTest {

    @TempDir
    lateinit var tempDir: Path

    private val queuesRoot by lazy { tempDir / "event-queues" }

    @Test
    fun liveStoragesDoNotSeeEachOthersFiles() = runTestWithMdc {
        val storage1 = ProcessOwnedEventLogStorage.create(queuesRoot)
        val storage2 = ProcessOwnedEventLogStorage.create(queuesRoot)

        storage1.write("events-1.log", "event1".toByteArray())
        storage2.write("events-2.log", "event2".toByteArray())

        assertEquals(listOf("events-1.log"), storage1.list(""))
        assertEquals(listOf("events-2.log"), storage2.list(""))

        storage1.close()
        storage2.close()
    }

    @Test
    fun filesOfLiveStorageAreNotAdopted() = runTestWithMdc {
        val liveStorage = ProcessOwnedEventLogStorage.create(queuesRoot)
        liveStorage.write("events.log", "event".toByteArray())

        val newStorage = ProcessOwnedEventLogStorage.create(queuesRoot)

        assertEquals(emptyList(), newStorage.list(""))
        assertEquals(listOf("events.log"), liveStorage.list(""))

        liveStorage.close()
        newStorage.close()
    }

    @Test
    fun filesOfClosedStorageAreAdoptedByNewStorage() = runTestWithMdc {
        repeat(3) {
            val closedStorage = ProcessOwnedEventLogStorage.create(queuesRoot)
            closedStorage.write("events-$it.log", "event-$it".toByteArray())
            closedStorage.write("events-$it.log.meta", "123".toByteArray())
            closedStorage.close()
        }

        val newStorage = ProcessOwnedEventLogStorage.create(queuesRoot)
        newStorage.write("events-new.log", "event".toByteArray())

        assertEquals(
            [
                "events-0.log",
                "events-0.log.meta",
                "events-1.log",
                "events-1.log.meta",
                "events-2.log",
                "events-2.log.meta",
                "events-new.log",
            ],
            newStorage.list("").toSet()
        )
        assertEquals("event-0", newStorage.read("events-0.log")?.decodeToString())
        assertEquals("event-1", newStorage.read("events-1.log")?.decodeToString())
        assertEquals("event-2", newStorage.read("events-2.log")?.decodeToString())
        assertEquals(1, queuesRoot.listDirectoryEntries().count { it.toFile().isDirectory }, "the adopted directories should be deleted")

        newStorage.close()
    }
}
