/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution

import org.jetbrains.amper.dependency.resolution.DependencyFileImpl.Companion.HttpHeaders.USER_AGENT
import org.jetbrains.amper.dependency.resolution.DependencyFileImpl.Companion.HttpHeaders.USER_AGENT_VALUE
import org.junit.jupiter.api.fail
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

class TestHttpClient(
    val client: HttpClient,
    private val errorProducingUrls: List<String>,
    private val overriddenUrl: Map<String, OverriddenUrl>,
    private val failOnErrorUrl: Boolean = true,
) : HttpClient() {

    val processedUrls: MutableMap<URI, Int> = mutableMapOf()

    override fun cookieHandler(): Optional<CookieHandler?>? = client.cookieHandler()
    override fun connectTimeout(): Optional<Duration?>? = client.connectTimeout()
    override fun followRedirects(): Redirect? = client.followRedirects()
    override fun proxy(): Optional<ProxySelector?>? = client.proxy()
    override fun sslContext(): SSLContext? = client.sslContext()
    override fun sslParameters(): SSLParameters? = client.sslParameters()
    override fun authenticator(): Optional<Authenticator?>? = client.authenticator()
    override fun version(): Version? = client.version()
    override fun executor(): Optional<Executor?>? = client.executor()
    override fun <T> send(p0: HttpRequest?, p1: HttpResponse.BodyHandler<T>?): HttpResponse<T> =
        withUrlsCheck(p0) { overriddenRequest -> client.send(overriddenRequest ?: p0, p1) }

    override fun <T> sendAsync(p0: HttpRequest?, p1: HttpResponse.BodyHandler<T>?): CompletableFuture<HttpResponse<T>> =
        withUrlsCheck(p0) { overriddenRequest ->
            client.sendAsync(overriddenRequest ?: p0, p1)
        }

    override fun <T> sendAsync(
        p0: HttpRequest?,
        p1: HttpResponse.BodyHandler<T>?,
        p2: HttpResponse.PushPromiseHandler<T>?
    ): CompletableFuture<HttpResponse<T>> =
        withUrlsCheck(p0) { overriddenRequest ->
            client.sendAsync(overriddenRequest ?: p0, p1, p2)
        }

    private fun <T> withUrlsCheck(request: HttpRequest?, block: (HttpRequest?) -> T): T {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
            returnsResultOf(block)
        }
        // Only GET requests download files, HEAD requests just check that the file exists
        val downloadRequest = request?.takeIf { it.method() == "GET" }
        downloadRequest?.let {
            if (errorProducingUrls.any { request.uri() == URI.create(it) }) {
                val message = "Request to one of error-producing URLs: ${request.uri()}"
                if (failOnErrorUrl) fail(message) else  error(message)
            }

            processedUrls[request.uri()] = (processedUrls[request.uri()] ?: 0) + 1
        }

        val overriddenRequest =
            downloadRequest
                ?.let { overriddenUrl[request.uri().toURL().toString()] }
                ?.let { overriddenContent ->
                    if (overriddenContent.overriddenAttempts == null
                        || processedUrls[request.uri()]!! <= overriddenContent.overriddenAttempts) {
                        HttpRequest.newBuilder()
                            .uri(URI.create(overriddenContent.value))
                            // User-agent header helps the repository provider to distinguish clients
                            // and give them a way to contact client authors if they would like to report misuse or any other issue.
                            // It is explicitly required by Maven Central, see
                            // https://central.sonatype.org/faq/429-tooling-provider/#identify-your-tool-in-the-user-agent
                            .header(USER_AGENT, USER_AGENT_VALUE)
                            .timeout(Duration.ofMinutes(2))
                            .GET()
                            .build()
                    } else {
                        null
                    }
                }

        return block(overriddenRequest)
    }

    companion object {
        /**
         * @param urlThatShouldNotBeDownloaded any attempt of HttpClient to download one of the URLs
         * ends up in either error being thrown or immediate test failure depending on the parameter [failOnErrorUrl]
         * (HEAD requests to these URLs are allowed)
         *
         * @param overriddenUrl map URL to a substitution and specifies how many times request URL should be substituted
         *
         * @param failOnErrorUrl set to true if the test should immediately [fail]
         * on an attempt to reach one of the given URLs [urlThatShouldNotBeDownloaded],
         * otherwise this HttpClient throws an error that resolution processes as any other error.
         */
        fun create(
            urlThatShouldNotBeDownloaded: List<String> = emptyList(),
            overriddenUrl: Map<String, OverriddenUrl> = emptyMap(),
            failOnErrorUrl: Boolean = true
        ): TestHttpClient {
            val client = newBuilder()
                .version(Version.HTTP_2)
                .followRedirects(Redirect.NORMAL)
                .sslContext(SSLContext.getDefault())
                .connectTimeout(Duration.ofSeconds(20))
                .build()
            return TestHttpClient(
                client = client,
                errorProducingUrls = urlThatShouldNotBeDownloaded,
                overriddenUrl = overriddenUrl,
                failOnErrorUrl = failOnErrorUrl)
        }
    }

    data class OverriddenUrl(
        val value: String,
        /**
         * Specify for how many consecutive attempts overridden content will be returned.
         * If not set, it will be returned for all URL requests.
         */
        val overriddenAttempts: Int? = null
    )
}