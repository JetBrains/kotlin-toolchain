/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.fus.client

import com.jetbrains.fus.reporting.FusHttpClient
import com.jetbrains.fus.reporting.HttpResponse
import org.slf4j.LoggerFactory

internal class LoggingFusHttpClient(val delegate: FusHttpClient) : FusHttpClient {

    private val logger = LoggerFactory.getLogger(LoggingFusHttpClient::class.java)

    override fun post(url: String, data: String): HttpResponse {
        fusEventDumpFile?.dumpSentEvent(data)
        return delegate.post(url, data)
    }

    override fun get(url: String): HttpResponse {
        logger.debug("FUS GET $url")
        return delegate.get(url)
    }

    override fun lastModified(url: String): Long {
        logger.debug("FUS HEAD $url")
        return delegate.lastModified(url)
    }

    override suspend fun close() {
        delegate.close()
    }
}
