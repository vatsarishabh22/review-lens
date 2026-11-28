package io.github.vatsarishabh22.reviewlens.host.github

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.net.ssl.CertificateManager
import io.github.vatsarishabh22.reviewlens.core.CliException
import java.net.Authenticator
import java.net.ConnectException
import java.net.ProxySelector
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.UnresolvedAddressException
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLHandshakeException

/**
 * GitHub's API over HTTPS as the viewer ([token]), on github.com or a GitHub Enterprise server ([host]). The JDK client,
 * because it never sends a POST twice: `HttpURLConnection` (under IntelliJ's `HttpRequests`) re-sent one on a dropped
 * keep-alive connection, measured, and a re-sent Finish posts twice. The IDE's proxy and trusted certificates apply.
 */
class GitHubApi(
    private val host: String, private val token: String,
    private val rest: String = apiRoots(host).first,
    private val graphqlUrl: String = apiRoots(host).second,
    private val http: HttpClient = ideClient,
) {

    /**
     * One GraphQL call's `data`, waiting at most [waitMs] of its [timeoutMs]. An error in a 200 is GitHub saying no when it
     * has a `type` (`NOT_FOUND`), or `locations`/`extensions`, which every error raised before running carries (parse,
     * field, variable, measured); one with none is GitHub's documented "may be the result of a timeout" and may have landed.
     */
    fun graphql(body: String, timeoutMs: Int, waitMs: Int = timeoutMs): JsonObject {
        val answer = send(HttpRequest.newBuilder(URI(graphqlUrl)).POST(HttpRequest.BodyPublishers.ofString(body)), timeoutMs, waitMs) as? JsonObject ?: throw unread()
        if (answer.has("errors")) {
            val reason = githubRefusal(answer.toString()) ?: "GitHub refused the call"
            val refused = (answer.get("errors") as? JsonArray)?.all { e -> listOf("type", "locations", "extensions").any { (e as? JsonObject)?.has(it) == true } } == true
            throw if (refused) CliException(reason) else stopped(reason)
        }
        return answer.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: throw unread()
    }

    /**
     * Every page of a GraphQL [query] whose [connection] (a path in `data`, like `repository.pullRequest.files`) has
     * `pageInfo{hasNextPage endCursor}` and takes `$endCursor`, all within one [timeoutMs] (spec rule 7: one read).
     */
    fun pages(query: String, variables: Map<String, Any?>, connection: String, timeoutMs: Int): List<JsonObject> {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        val pages = mutableListOf<JsonObject>()
        var cursor: String? = null
        do {
            val left = ((deadline - System.nanoTime()) / 1_000_000).toInt()
            if (left <= 0) throw stopped("GitHub didn't answer in ${timeoutMs / 1000} s")
            val data = graphql(graphqlBody(query, variables + ("endCursor" to cursor)), timeoutMs, left)
            pages += data
            val info = data.at(connection)?.getAsJsonObject("pageInfo")
            cursor = info?.get("endCursor")?.takeUnless { it.isJsonNull }?.asString
        } while (info?.get("hasNextPage")?.asBoolean == true && cursor != null)
        return pages
    }

    /** A REST read, [path] under the API root (`repos/acme/shop/compare/a...b`). */
    fun get(path: String, timeoutMs: Int): JsonElement = send(HttpRequest.newBuilder(URI("$rest/$path")).GET(), timeoutMs)

    /**
     * Sends one call, stopped after [waitMs] however the answer trickles in (spec rule 7: [timeoutMs] is the limit it says).
     * Every failure is one of three: never sent (nothing happened on GitHub), refused (GitHub said no), or `timedOut` (it may
     * have reached GitHub and no whole answer came back, so a write is read back before anything is undone).
     */
    private fun send(request: HttpRequest.Builder, timeoutMs: Int, waitMs: Int = timeoutMs): JsonElement {
        val call = http.sendAsync(request.header("Authorization", "bearer $token").header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "Review-Lens").header("Content-Type", "application/json").build(),
            HttpResponse.BodyHandlers.ofString())
        val response = try {
            call.get(waitMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            call.cancel(true)
            logger<GitHubApi>().info("GitHub call stopped after $timeoutMs ms")
            throw stopped("GitHub didn't answer in ${timeoutMs / 1000} s")
        } catch (e: InterruptedException) {
            call.cancel(true)
            Thread.currentThread().interrupt()
            throw stopped("Stopped before GitHub answered")
        } catch (e: ExecutionException) {
            val cause = e.cause
            logger<GitHubApi>().info("GitHub call failed: $cause")
            // never sent: no connection, or no secure one (an untrusted certificate, often a company proxy's)
            if (cause is ConnectException || cause is HttpConnectTimeoutException || cause is UnknownHostException || cause?.cause is UnresolvedAddressException) throw CliException("Can't reach $host")
            if (cause is SSLHandshakeException) throw CliException("Can't connect securely to $host: the IDE doesn't trust its certificate")
            throw stopped("GitHub's answer was cut off")
        }
        val body = response.body()
        // GitHub failed or its gateway gave up waiting: the call may have done its work, or still finish there
        if (response.statusCode() >= 500) throw stopped("GitHub didn't finish answering (${response.statusCode()})")
        if (response.statusCode() !in 200..299) throw CliException(githubRefusal(body) ?: "GitHub answered ${response.statusCode()}", stdout = body)
        return runCatching { JsonParser.parseString(body) }.getOrNull()?.takeUnless { it.isJsonNull } ?: throw unread()
    }

    private fun stopped(reason: String) = CliException(reason, stderr = reason, timedOut = true)

    /** A 200 whose answer can't be read: the call reached GitHub, so what it did there is unknown. */
    private fun unread() = stopped("GitHub's answer couldn't be read")

    private companion object {
        // the IDE installs its proxy settings as the JDK defaults (measured in the sandbox); its authenticator answers a
        // proxy's 407 only, as GitHub's 401 carries no challenge (measured on api.github.com)
        val ideClient: HttpClient by lazy {
            HttpClient.newBuilder().proxy(ProxySelector.getDefault()).apply { Authenticator.getDefault()?.let(::authenticator) }
                .sslContext(CertificateManager.getInstance().sslContext).connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()
        }
    }
}

/**
 * The REST and GraphQL roots for [host]: github.com and Cloud with data residency (`<tenant>.ghe.com`) at `api.<host>`, a
 * GitHub Enterprise server under `/api`, as GitHub documents them; ponytail: unmeasured beyond github.com, no server to try.
 */
fun apiRoots(host: String): Pair<String, String> =
    if (host == "github.com" || host.endsWith(".ghe.com")) "https://api.$host" to "https://api.$host/graphql" else "https://$host/api/v3" to "https://$host/api/graphql"

/** The object at a dotted [path] (`repository.pullRequest`), or null where GitHub answered null or nothing. */
fun JsonObject.at(path: String): JsonObject? = path.split('.').fold(this as JsonObject?) { o, key -> o?.get(key)?.takeIf { it.isJsonObject }?.asJsonObject }
