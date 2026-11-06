/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.client

import com.jetbrains.fus.reporting.MetadataStorage
import com.jetbrains.fus.reporting.api.IGroupValidators
import com.jetbrains.fus.reporting.api.RecorderDataValidationRule
import com.jetbrains.fus.reporting.api.emptyGroupValidators
import org.jetbrains.amper.test.runTestWithMdc
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.div
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProcessSafeMetadataStorageTest {

    @TempDir
    lateinit var tempDir: Path

    private val lockFile by lazy { tempDir / "metadata.lock" }

    @Test
    fun creationIsDoneUnderLock() = runTestWithMdc {
        var created = false
        ProcessSafeMetadataStorage.create(lockFile) {
            assertLockHeld()
            created = true
            FakeMetadataStorage(onAccess = {})
        }
        assertTrue(created, "the delegate should be created eagerly")
    }

    @Test
    fun updateIsDoneUnderLock() = runTestWithMdc {
        var accessCount = 0
        val storage = ProcessSafeMetadataStorage.create(lockFile) {
            FakeMetadataStorage(onAccess = { assertLockHeld(); accessCount++ })
        }
        assertTrue(storage.update())
        assertEquals(1, accessCount)
    }

    @Test
    fun reloadIsDoneUnderLock() = runTestWithMdc {
        var accessCount = 0
        val storage = ProcessSafeMetadataStorage.create(lockFile) {
            FakeMetadataStorage(onAccess = { assertLockHeld(); accessCount++ })
        }
        storage.reload()
        assertEquals(1, accessCount)
    }

    // A lock held by this JVM on an overlapping region makes tryLock() fail with OverlappingFileLockException
    private fun assertLockHeld() {
        FileChannel.open(lockFile, StandardOpenOption.WRITE, StandardOpenOption.CREATE).use { channel ->
            assertFailsWith<OverlappingFileLockException>("the cache lock should be held") {
                channel.tryLock()?.release()
            }
        }
    }
}

private class FakeMetadataStorage(private val onAccess: () -> Unit) : MetadataStorage<String> {
    override suspend fun scheduleUpdate() = error("not expected")
    override suspend fun update(): Boolean {
        onAccess()
        return true
    }
    override suspend fun reload() = onAccess()
    override fun getFieldsToAnonymize(groupId: String, eventId: String): Set<String> = []
    override fun getSkipAnonymizationIds(): Set<String> = []
    override fun getGroupValidators(groupId: String): IGroupValidators<String> = emptyGroupValidators()
    override fun isUnreachable(): Boolean = false
    override fun getSystemDataRulesRevisions() = RecorderDataValidationRule([])
    override fun getClientDataRulesRevisions() = RecorderDataValidationRule([])
    override fun getIdsRulesRevisions() = RecorderDataValidationRule([])
}
