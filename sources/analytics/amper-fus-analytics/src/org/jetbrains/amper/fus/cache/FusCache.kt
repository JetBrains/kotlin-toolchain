/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.cache

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div

class FusCache(private val root: Path) {
    val machineIdFile = root / "machine-id"
    val anonymizationSaltFile = root / "anonymization_salt"
    val consentStateLastReportFile = root / "consent-state-last-report"

    val metadataDir by lazy { (root / "metadata").createDirectories() }
    val eventLogDir by lazy { (root / "events").createDirectories() }
}
