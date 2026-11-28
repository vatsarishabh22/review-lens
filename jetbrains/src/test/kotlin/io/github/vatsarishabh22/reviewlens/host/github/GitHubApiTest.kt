package io.github.vatsarishabh22.reviewlens.host.github

import com.sun.net.httpserver.HttpServer
import io.github.vatsarishabh22.reviewlens.core.CliException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

/** GitHubApi against a local server: refusals, pages, the wall-clock stop, and a write never sent twice. */
class GitHubApiTest {
    private val token = "tok-secret-1"
    private val bodies = Collections.synchronizedList(mutableListOf<String>())
    private val auth = Collections.synchronizedList(mutableListOf<String?>())
    private lateinit var server: HttpServer

    /** A server answering each call with the next of [answers] (status to body). */
    private fun serve(vararg answers: Pair<Int, String>): GitHubApi {
        val queue = ArrayDeque(answers.toList())
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { x ->
                bodies += x.requestBody.readAllBytes().decodeToString(); auth += x.requestHeaders.getFirst("Authorization")
                val (status, body) = synchronized(queue) { queue.removeFirst() }
                x.sendResponseHeaders(status, body.length.toLong()); x.responseBody.use { it.write(body.toByteArray()) }
            }
            start()
        }
        return api("http://127.0.0.1:${server.address.port}")
    }

    // its own client: the IDE's needs a running IDE, which this plain test doesn't start
    private fun api(root: String) = GitHubApi("github.com", token, rest = root, graphqlUrl = "$root/graphql", http = java.net.http.HttpClient.newHttpClient())

    @After
    fun stop() {
        if (::server.isInitialized) server.stop(0)
    }

    private fun fails(call: () -> Unit): CliException = try { call(); throw AssertionError("no failure") } catch (e: CliException) { e }

    @Test
    fun `a GraphQL refusal sent with a 200 throws GitHub's reason, the token in no message`() {
        val api = serve(200 to """{"data":{"repository":{"pullRequest":null}},"errors":[{"type":"NOT_FOUND","message":"Could not resolve to a PullRequest with the number of 999."}]}""")
        val e = fails { api.graphql(graphqlBody("query{x}", emptyMap()), 5_000) }
        assertEquals("Could not resolve to a PullRequest with the number of 999.", e.message)
        assertFalse(e.timedOut)
        assertEquals("bearer $token", auth.single())
        assertFalse(token in e.message.orEmpty() + e.stdout + e.stderr)
    }

    /**
     * GitHub's documented answer when a call ran out of time on its side: a 200, an error with no `type`. The refusals are
     * api.github.com's own answers (2026-11-28): every error raised before running carries `locations`.
     */
    @Test
    fun `a GraphQL error GitHub raised before running is a refusal, one with no type or location may have reached GitHub`() {
        listOf(
            """{"errors":[{"path":["query","viewer","nope"],"extensions":{"code":"undefinedField","typeName":"User","fieldName":"nope"},"locations":[{"line":1,"column":9}],"message":"Field 'nope' doesn't exist on type 'User'"}]}""",
            """{"errors":[{"locations":[{"line":1,"column":8}],"message":"Expected NAME, actual: (none) (\"\") at [1, 8]"}]}""",
            """{"errors":[{"extensions":{"value":"x","problems":[{"path":[],"explanation":"Could not coerce value \"x\" to Int"}]},"locations":[{"line":1,"column":7}],"message":"Variable ${'$'}n of type Int! was provided invalid value"}]}""",
        ).forEach { body ->
            assertFalse(body, fails { serve(200 to body).graphql("{}", 5_000) }.timedOut)
            server.stop(0)
        }
        val untyped = """{"data":null,"errors":[{"message":"Something went wrong while executing your query. This may be the result of a timeout, or it could be a GitHub bug."}]}"""
        val e = fails { serve(200 to untyped).graphql("{}", 5_000) }
        assertTrue(e.timedOut)
        assertTrue(e.message!!.startsWith("Something went wrong"))
        server.stop(0)
        val mixed = """{"errors":[{"type":"NOT_FOUND","message":"a"},{"message":"b"}]}"""
        assertTrue("one untyped error is enough", fails { serve(200 to mixed).graphql("{}", 5_000) }.timedOut)
    }

    @Test
    fun `the API roots on github_com, a data residency tenant and an Enterprise server`() {
        assertEquals("https://api.github.com" to "https://api.github.com/graphql", apiRoots("github.com"))
        assertEquals("https://api.acme.ghe.com" to "https://api.acme.ghe.com/graphql", apiRoots("acme.ghe.com"))
        assertEquals("https://git.acme.com/api/v3" to "https://git.acme.com/api/graphql", apiRoots("git.acme.com"))
    }

    @Test
    fun `a REST refusal throws GitHub's message`() {
        val api = serve(401 to """{"message":"Bad credentials","status":"401"}""")
        assertEquals("Bad credentials", fails { api.get("user", 5_000) }.message)
    }

    @Test
    fun `pages follow the cursor until GitHub says there are no more`() {
        fun page(more: Boolean, cursor: String?, path: String) =
            200 to """{"data":{"repository":{"pullRequest":{"files":{"pageInfo":{"hasNextPage":$more,"endCursor":${cursor?.let { "\"$it\"" } ?: "null"}},"nodes":[{"path":"$path"}]}}}}}"""
        val api = serve(page(true, "c1", "a"), page(false, "c2", "b"))
        val pages = api.pages("query", mapOf("number" to 1), "repository.pullRequest.files", 5_000)
        assertEquals(listOf("a", "b"), pages.map { it.at("repository.pullRequest.files")!!.getAsJsonArray("nodes")[0].asJsonObject["path"].asString })
        assertEquals(listOf(false, true), bodies.map { "\"endCursor\":\"c1\"" in it })
    }

    /** Spec rule 7: one limit for the whole read, as `gh --paginate` had, never one per page. */
    @Test
    fun `a read of several pages is stopped at one limit for all of them`() {
        var served = 0
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { x ->
                x.requestBody.readAllBytes(); Thread.sleep(400)
                val page = """{"data":{"x":{"pageInfo":{"hasNextPage":${++served < 4},"endCursor":"c"}}}}""" // 4 pages of 0.4 s: each in time, all not
                x.sendResponseHeaders(200, page.length.toLong()); x.responseBody.use { it.write(page.toByteArray()) }
            }
            start()
        }
        val started = System.nanoTime()
        val e = fails { api("http://127.0.0.1:${server.address.port}").pages("query", emptyMap(), "x", 1_000) }
        val took = (System.nanoTime() - started) / 1_000_000
        assertTrue(e.timedOut)
        assertTrue("stopped near the one limit, took $took ms", took < 1_600)
        assertEquals("GitHub didn't answer in 1 s", e.message)
    }

    @Test
    fun `a call is stopped at its limit however slowly the answer trickles in`() {
        val socket = ServerSocket(0)
        thread(isDaemon = true) {
            socket.accept().use { c ->
                c.getInputStream().read(ByteArray(4096))
                val out = c.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n".toByteArray()); out.flush()
                repeat(100) { out.write(' '.code); out.flush(); Thread.sleep(100) } // a byte each 100 ms: no read ever waits long
            }
        }
        val started = System.nanoTime()
        val e = fails { api("http://127.0.0.1:${socket.localPort}").graphql("{}", 1_000) }
        val took = (System.nanoTime() - started) / 1_000_000
        socket.close()
        assertTrue(e.timedOut)
        assertEquals("GitHub didn't answer in 1 s", e.message)
        assertTrue("stopped near its limit, took $took ms", took < 3_000)
    }

    @Test
    fun `a server that can't be reached is no write, nothing happened on GitHub`() {
        val port = ServerSocket(0).use { it.localPort }
        val e = fails { api("http://127.0.0.1:$port").graphql("{}", 5_000) }
        assertEquals("Can't reach github.com", e.message)
        assertFalse(e.timedOut)
    }

    @Test
    fun `a server error, a gateway that gave up, or a 200 that can't be read, may have reached GitHub`() {
        listOf(500 to """{"message":"Server Error"}""", 502 to "<html>Bad gateway</html>", 504 to "", 200 to "not json", 200 to """{"data":null}""").forEach { (status, body) ->
            val e = fails { serve(status to body).graphql("{}", 5_000) }
            assertTrue("$status $body", e.timedOut)
            server.stop(0)
        }
        assertFalse("a 4xx is GitHub saying no", fails { serve(422 to """{"message":"Unprocessable Entity"}""").graphql("{}", 5_000) }.timedOut)
    }

    /**
     * A certificate the client doesn't trust, refused in the handshake before anything is sent. The JDK's own trust here,
     * as the IDE's test mode accepts any certificate (measured).
     */
    @Test
    fun `an untrusted certificate is a call never sent, said as such`() {
        val dir = java.nio.file.Files.createTempDirectory("rl-tls")
        val store = dir.resolve("self.p12").toString()
        val keytool = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
        ProcessBuilder(keytool, "-genkeypair", "-alias", "s", "-keyalg", "RSA", "-dname", "CN=127.0.0.1", "-validity", "2", "-storetype", "PKCS12",
            "-keystore", store, "-storepass", "secret1", "-keypass", "secret1").inheritIO().start().waitFor()
        val keys = java.security.KeyStore.getInstance("PKCS12").apply { java.io.FileInputStream(store).use { load(it, "secret1".toCharArray()) } }
        val ssl = javax.net.ssl.SSLContext.getInstance("TLS").apply {
            init(javax.net.ssl.KeyManagerFactory.getInstance("SunX509").apply { init(keys, "secret1".toCharArray()) }.keyManagers, null, null)
        }
        val https = com.sun.net.httpserver.HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = com.sun.net.httpserver.HttpsConfigurator(ssl)
            createContext("/") { x -> bodies += x.requestBody.readAllBytes().decodeToString(); x.sendResponseHeaders(200, 2); x.responseBody.use { it.write("{}".toByteArray()) } }
            start()
        }
        try {
            val root = "https://127.0.0.1:${https.address.port}"
            val jdkTrust = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as java.security.KeyStore?) }
            val strict = java.net.http.HttpClient.newBuilder().sslContext(javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, jdkTrust.trustManagers, null) }).build()
            val e = fails { GitHubApi("github.com", token, root, "$root/graphql", strict).graphql("{}", 5_000) }
            assertEquals("Can't connect securely to github.com: the IDE doesn't trust its certificate", e.message)
            assertFalse(e.timedOut)
            assertEquals("nothing reached the server", emptyList<String>(), bodies.toList())
        } finally {
            https.stop(0)
        }
    }

    /** The case that made this the JDK client: a dropped keep-alive connection, where HttpURLConnection sent the write again (measured). */
    @Test
    fun `a write cut off after it was sent is sent once and can't be told`() {
        val socket = ServerSocket(0)
        val received = Collections.synchronizedList(mutableListOf<String>())
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val c = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    c.use {
                        val input = it.getInputStream().bufferedReader()
                        var served = 0
                        while (true) {
                            val headers = generateSequence { input.readLine() }.takeWhile { line -> line.isNotEmpty() }.toList() // every header, or the body read starts inside them
                            val length = headers.firstOrNull { line -> line.startsWith("Content-Length:", ignoreCase = true) }?.substringAfter(':')?.trim()?.toInt() ?: break
                            received += CharArray(length).also { buf -> input.read(buf) }.concatToString()
                            if (++served == 2) break // the second write on this connection: read, then dropped unanswered
                            it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 11\r\n\r\n{\"data\":{}}".toByteArray())
                        }
                    }
                }
            }
        }
        val api = api("http://127.0.0.1:${socket.localPort}")
        api.graphql("""{"n":1}""", 5_000)
        val e = fails { api.graphql("""{"n":2}""", 5_000) }
        Thread.sleep(300)
        socket.close()
        assertTrue(e.timedOut)
        assertEquals(listOf("""{"n":1}""", """{"n":2}"""), received.toList())
    }
}
