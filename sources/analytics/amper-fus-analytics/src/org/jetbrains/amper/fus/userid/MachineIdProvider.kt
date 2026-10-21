/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.userid

import org.jetbrains.amper.fus.cache.FusCache
import org.jetbrains.amper.stdlib.hashing.sha256String
import org.jetbrains.amper.system.info.OsFamily
import org.jetbrains.amper.system.info.SystemInfo
import org.slf4j.LoggerFactory
import kotlin.io.path.Path
import kotlin.io.path.readText
import org.jetbrains.amper.windows.registry.WindowsRegistry
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.uuid.Uuid

internal object MachineIdProvider {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * An anonymized ID representing the current machine.
     *
     * The algorithm is similar to IntelliJ's `EventLogConfiguration.generateMachineId`, so events can be matched.
     */
    fun getOrCreateMachineId(fusCache: FusCache): String {
        val machineIdFile = fusCache.machineIdFile
        if (machineIdFile.exists()) {
            return machineIdFile.readText().trim()
        }
        val machineId = computeAnonymizedMachineId()
        machineIdFile.writeText(machineId)
        return machineId
    }

    private fun computeAnonymizedMachineId(): String {
        val machineId = findMachineId() ?: Uuid.random().toHexString()
        val saltedId = buildString {
            append(System.getProperty("user.name"))
            append("JetBrainsFUS")
            append(machineId)
        }
        return saltedId.sha256String()
    }

    private fun findMachineId(): String? = try {
        when (SystemInfo.CurrentHost.family) {
            OsFamily.Windows -> getWindowsMachineId()
            OsFamily.MacOs -> MacOSMachineId.findUuid()
            OsFamily.Linux -> getLinuxMachineId()
            OsFamily.FreeBSD -> getFreeBSDMachineId()
            OsFamily.Solaris -> null
        }
    } catch (e: Throwable) {
        logger.debug("Couldn't read machine ID", e)
        null
    }

    /**
     * See [MachineGuid](https://learn.microsoft.com/en-us/answers/questions/1489139/identifying-unique-windows-installation).
     */
    private fun getWindowsMachineId(): String? = WindowsRegistry.getString(
        hive = WindowsRegistry.Hive.LOCAL_MACHINE,
        key = "SOFTWARE\\Microsoft\\Cryptography",
        value = "MachineGuid",
        // The length of a UUID in its canonical textual form, as stored in `MachineGuid` in the Windows registry.
        expectedMaxLength = 36,
    )

    /**
     * See [MACHINE-ID(5)](https://manpages.debian.org/testing/systemd/machine-id.5.en.html).
     */
    private fun getLinuxMachineId(): String? =
        sequenceOf(
            "/etc/machine-id",
            "/var/lib/dbus/machine-id",
            "/sys/devices/virtual/dmi/id/product_uuid",
        ).firstNotNullOfOrNull { Path(it).readTrimmedNonEmpty() }

    private fun getFreeBSDMachineId(): String? =
        sequenceOf(
            "/etc/machine-id",
            "/var/lib/dbus/machine-id",
            "/etc/hostid",
        ).firstNotNullOfOrNull { Path(it).readTrimmedNonEmpty() }

    private fun Path.readTrimmedNonEmpty(): String? = readText().trim().takeIf(String::isNotEmpty)
}
