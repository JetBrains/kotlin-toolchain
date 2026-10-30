/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.schema

/**
 * A range of module counts, used to report the number of modules in FUS events without reporting the exact count.
 */
enum class FusModuleCountBucket(
    /**
     * The module counts that fall into this bucket.
     */
    val range: IntRange,
) : FusEnum<FusModuleCountBucket> {
    BUCKET_0(range = 0..0),
    BUCKET_1(range = 1..1),
    BUCKET_2(range = 2..2),
    BUCKET_3_5(range = 3..5),
    BUCKET_6_10(range = 6..10),
    BUCKET_11_20(range = 11..20),
    BUCKET_21_50(range = 21..50),
    BUCKET_51_100(range = 51..100),
    BUCKET_101_300(range = 101..300),
    BUCKET_301_PLUS(range = 301..Int.MAX_VALUE),
    ;

    override val fusId: String = when {
        range.first == range.last -> range.first.toString()
        range.last == Int.MAX_VALUE -> "${range.first}+"
        else -> "${range.first}-${range.last}"
    }

    companion object {
        fun of(moduleCount: Int): FusModuleCountBucket = entries.firstOrNull { moduleCount in it.range }
            ?: error("Missing bucket for module count $moduleCount. Known buckets: ${entries.joinToString { it.fusId }}")
    }
}
