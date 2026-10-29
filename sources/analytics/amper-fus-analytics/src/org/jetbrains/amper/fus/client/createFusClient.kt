/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.client

import com.intellij.internal.statistic.eventLog.connection.metadata.EventLogBuildParser
import com.jetbrains.fus.reporting.FusClient
import com.jetbrains.fus.reporting.METADATA_LOADED_TOPIC
import com.jetbrains.fus.reporting.METADATA_LOAD_FAILED_TOPIC
import com.jetbrains.fus.reporting.RegionCode
import com.jetbrains.fus.reporting.components
import com.jetbrains.fus.reporting.config
import com.jetbrains.fus.reporting.defaults.defaultBusinessLogic
import com.jetbrains.fus.reporting.eventLogFileStorage
import com.jetbrains.fus.reporting.fileStorage
import com.jetbrains.fus.reporting.fusClient
import com.jetbrains.fus.reporting.httpClient
import com.jetbrains.fus.reporting.jsonSerializer
import com.jetbrains.fus.reporting.jvm.JvmFileStorage
import com.jetbrains.fus.reporting.jvm.JvmHttpClient
import com.jetbrains.fus.reporting.loggerFactory
import com.jetbrains.fus.reporting.messageHandler
import com.jetbrains.fus.reporting.model.lion4.FusReport
import com.jetbrains.fus.reporting.model.lion4.LogEvent
import com.jetbrains.fus.reporting.serialization.FusKotlinSerializer
import org.apache.maven.artifact.versioning.ComparableVersion
import org.jetbrains.amper.buildinfo.AmperBuild
import org.jetbrains.amper.fus.FusConstants
import org.jetbrains.amper.fus.cache.FusCache
import org.jetbrains.amper.fus.schema.KtcFusRecorder
import org.jetbrains.amper.fus.schema.MACHINE_ID_KEY
import org.jetbrains.amper.fus.userid.isInternalUserForFUS
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.util.*
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.uuid.Uuid

private val logger = LoggerFactory.getLogger("FUS-setup")

// TODO This can be made private once we have confirmed the consent banner
// TODO Default to true once we confirmed things work
val FUS_ENABLED = System.getenv("KOTLIN_TOOLCHAIN_FUS_ENABLED")?.toBooleanStrictOrNull() ?: false

// TODO Default to false (production) once we confirmed things work
val FUS_USE_STAGING = System.getenv("KOTLIN_TOOLCHAIN_FUS_USE_STAGING")?.toBooleanStrictOrNull() ?: true

internal fun createFusClient(fusCache: FusCache): FusClient<LogEvent, FusReport> = fusClient {
    config {
        // TODO check this version choice
        // Use unversioned metadata.
        baselineVersion = 0

        productName = FusConstants.PRODUCT_NAME
        productCode = FusConstants.PRODUCT_CODE
        productVersion = AmperBuild.mavenVersion

        recorderCode = KtcFusRecorder.id
        recorderVersion = KtcFusRecorder.version.toString()

        anonymizationSalt = getOrGenerateAnonymizationSalt(fusCache)
        // TODO check whether this is enough, we might need to make this overridable
        regionCode = if (Locale.getDefault().country == "CN") RegionCode.CN else RegionCode.ALL

        isTest = FUS_USE_STAGING

        // This is not based on consent because we skip the entire FUS client creation if consent is not given.
        isLoggingEnabled = { FUS_ENABLED }
        isRecordEnabled = { FUS_ENABLED }
        isSendEnabled = { FUS_ENABLED }
    }

    components {
        loggerFactory { Slf4jFusLoggerFactory }
        httpClient { config ->
            JvmHttpClient(userAgent = "${config.productName}/${config.productVersion}")
        }
        fileStorage {
            JvmFileStorage(fusCache.metadataDir)
        }
        eventLogFileStorage {
            JvmFileStorage(fusCache.eventLogDir)
        }
        // TODO bundle the metadata so we don't pay the initial fetch
//        bundledFileStorage {
//            ResourceFileStorage(
//                resourceRootPath = "event-log-metadata",
//                classLoader = FusConstants::class.java.classLoader,
//            )
//        }

        jsonSerializer { FusKotlinSerializer() }
        defaultBusinessLogic {
            systemLogGroupId = "ktc.event.log"
            skipAnonymizationIds = setOf(MACHINE_ID_KEY)

            @Suppress("UnstableApiUsage")
            buildParser = EventLogBuildParser { versionValue ->
                // ComparableVersion doesn't know 'dev' so it places it higher than stable versions.
                // We work around this by replacing 'dev' with 'alpha', which is correctly considered lower.
                versionValue?.replace("-dev-", "-alpha-")?.let(::ComparableVersion)
            }
        }
    }

    messageHandler(topic = METADATA_LOADED_TOPIC) { version ->
        logger.debug("FUS metadata was loaded with version {}", version)
    }
    messageHandler(topic = METADATA_LOAD_FAILED_TOPIC) { loadError ->
        // Debug level for external users so it's not printed to the console.
        // For internal users, we make the error more visible.
        // Revisit this when we separate logging from console output better.
        val level = if (isInternalUserForFUS) Level.ERROR else Level.DEBUG
        logger.atLevel(level).log("Error loading FUS metadata: {}", loadError)
    }
}

private fun getOrGenerateAnonymizationSalt(fusCache: FusCache): String {
    val saltFile = fusCache.anonymizationSaltFile
    if (!saltFile.exists()) {
        val salt = Uuid.random().toHexString()
        saltFile.createParentDirectories().writeText(salt)
        return salt
    }
    return saltFile.readText()
}
