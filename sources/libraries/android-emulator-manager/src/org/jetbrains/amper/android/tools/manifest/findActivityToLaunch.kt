/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.android.tools.manifest

import kotlinx.serialization.*
import nl.adaptivity.xmlutil.serialization.*
import java.nio.file.*
import kotlin.io.path.*

private val xml = XML {
    defaultPolicy {
        ignoreUnknownChildren()
        repairNamespaces = false
    }
}

private const val namespace = "http://schemas.android.com/apk/res/android"
private const val prefix = "android"

/**
 * Finds the main launcher activity in the Android manifest at the given [manifestPath].
 */
fun findMainLauncherActivity(manifestPath: Path): String? = parseManifest(manifestPath)
    .application
    .activities
    .firstOrNull { activity ->
        val isMain = activity.intentFilters.any { it.action.name == "android.intent.action.MAIN" }
        val isLauncher = activity.intentFilters.any { it.category.name == "android.intent.category.LAUNCHER" }
        isMain && isLauncher
    }
    ?.name

private fun parseManifest(manifestPath: Path) = xml.decodeFromString<AndroidManifest>(manifestPath.readText())

@Serializable
@XmlSerialName("manifest")
private data class AndroidManifest(@XmlElement(true) val application: Application) {
    @Serializable
    @XmlSerialName("application")
    data class Application(
        @XmlElement(true)
        @XmlSerialName("activity")
        val activities: List<Activity>,
    ) {
        @Serializable
        @XmlSerialName("activity")
        data class Activity(
            @XmlSerialName("name", namespace, prefix)
            val name: String,
            @XmlElement(true)
            @XmlSerialName("intent-filter")
            val intentFilters: List<IntentFilter>,
        ) {
            @Serializable
            @XmlSerialName("intent-filter")
            data class IntentFilter(
                @XmlElement(true)
                @XmlSerialName("action")
                val action: Action,
                @XmlElement(true)
                @XmlSerialName("category")
                val category: Category,
            ) {
                @Serializable
                @XmlSerialName("action")
                data class Action(
                    @XmlSerialName("name", namespace, prefix)
                    val name: String,
                )

                @Serializable
                @XmlSerialName("category")
                data class Category(
                    @XmlSerialName("name", namespace, prefix)
                    val name: String,
                )
            }
        }
    }
}
