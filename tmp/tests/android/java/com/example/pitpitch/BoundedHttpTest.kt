package com.example.pitpitch

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.*
import com.example.pitpitch.data.db.*
import com.example.pitpitch.data.repository.SportsHttpException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real loopback sockets; the debug network policy allows only loopback cleartext. */
@RunWith(AndroidJUnit4::class)
class BoundedHttpTest {
    private var previousProxy: ProxySelector? = null

    @Before fun keepOnlyTestLoopbackConnectionsDirect() {
        previousProxy = ProxySelector.getDefault()
        val original = previousProxy
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> =
                if (uri.host == "127.0.0.1" || uri.host == "localhost") listOf(Proxy.NO_PROXY)
                else original?.select(uri) ?: listOf(Proxy.NO_PROXY)
            override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) {
                original?.connectFailed(uri, address, error)
            }
        })
    }

    @After fun restoreProcessProxySelector() { ProxySelector.setDefault(previousProxy) }

    private class Server(private val respond: (Socket) -> Unit) : AutoCloseable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${listener.localPort}/fixture"
        val requested = CountDownLatch(1)
        val finished = CountDownLatch(1)
        @Volatile private var connection: Socket? = null
        private val thread = Thread {
            try {
                listener.accept().use { socket ->
                    connection = socket
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { /* HTTP headers */ }
                    requested.countDown()
                    respond(socket)
                }
            } catch (_: IOException) {
                // Cancellation, timeout and over-limit responses close the actual socket.
            } finally { finished.countDown() }
        }.apply { isDaemon = true; start() }
        override fun close() {
            connection?.close()
            listener.close()
            thread.join(2_000)
        }
    }

    private suspend fun await(latch: CountDownLatch) = withContext(Dispatchers.IO) {
        assertTrue("Socket operation did not finish", latch.await(3, TimeUnit.SECONDS))
    }
    private fun OutputStream.headers(status: Int = 200, chunked: Boolean = false) {
        write(("HTTP/1.1 $status Test\r\n" +
            if (chunked) "Transfer-Encoding: chunked\r\nContent-Type: application/json\r\n\r\n"
            else "Content-Length: 0\r\n\r\n").toByteArray())
        flush()
    }
    private fun OutputStream.chunk(bytes: ByteArray) {
        write("${bytes.size.toString(16)}\r\n".toByteArray())
        write(bytes)
        write("\r\n".toByteArray())
        flush()
    }

    @Test fun cancellingBlockedHeaderAndBodyRequestsClosesPeerWithoutParsingOrCaching() = runBlocking {
        for (sendHeaders in listOf(false, true)) {
            val peerClosed = AtomicBoolean(false)
            Server { socket ->
                if (sendHeaders) socket.getOutputStream().headers(chunked = true)
                try {
                    peerClosed.set(socket.getInputStream().read() == -1)
                } catch (_: java.net.SocketException) {
                    // TCP reset is also an actual peer close; a socket timeout must still fail this assertion.
                    peerClosed.set(true)
                }
            }.use { server ->
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, PitPitchDatabase::class.java).build()
                try {
                    val parsed = AtomicBoolean(false)
                    val request = launch(Dispatchers.Default) {
                        JsonClient(database = db).load(server.url) { parsed.set(true); it }
                    }
                    await(server.requested)
                    withTimeout(2_000) { request.cancelAndJoin() }
                    await(server.finished)
                    assertTrue(peerClosed.get())
                    assertFalse(parsed.get())
                    withContext(Dispatchers.IO) { assertNull(db.localData().snapshot(server.url, "network")) }
                } finally { db.close() }
            }
        }
    }

    @Test fun continuouslyArrivingChunksStillHitTheTotalCallDeadline() = runBlocking {
        val sent = java.util.concurrent.atomic.AtomicInteger()
        Server { socket ->
            val output = socket.getOutputStream()
            output.headers(chunked = true)
            while (true) {
                output.chunk(byteArrayOf(' '.code.toByte()))
                sent.incrementAndGet()
                Thread.sleep(40)
            }
        }.use { server ->
            val parsed = AtomicBoolean(false)
            val start = System.nanoTime()
            try {
                withTimeout(3_000) {
                    JsonClient(timeoutMs = 1_000, totalTimeoutMs = 700).load(server.url) { parsed.set(true); it }
                }
                fail("Continuous chunks must not extend the total deadline")
            } catch (error: IOException) {
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                assertTrue("Total deadline did not bound the request: $elapsedMs", elapsedMs < 2_500)
                assertTrue("The peer must continuously provide chunks", sent.get() >= 3)
                assertFalse(parsed.get())
            }
            await(server.finished)
        }
    }

    @Test fun overLimitChunkedValidJsonKeepsOldBodyTimeAndStandings() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val body = instrumentation.context.assets.open("drivers.json").bufferedReader().use { it.readText() }
        val oldTime = "2026-01-01T00:00:00Z"
        val expected = JolpicaAdapter.standings(body, false, 2025)
        Server { socket ->
            val output = socket.getOutputStream()
            output.headers(chunked = true)
            output.chunk((body.trimEnd().removeSuffix("}") + ",\"padding\":\"").toByteArray())
            val block = ByteArray(64 * 1024) { 'a'.code.toByte() }
            repeat(257) { output.chunk(block) }
            output.chunk("\"}".toByteArray())
            output.write("0\r\n\r\n".toByteArray())
            output.flush()
        }.use { server ->
            val db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, PitPitchDatabase::class.java).build()
            try {
                db.localData().putSnapshots(listOf(SourceSnapshotRow(server.url, "network", body, oldTime, LocalDataImporter.sha(body.toByteArray()))))
                val store = FootballStore(db)
                store.writeStandings(2025, "driver", expected, oldTime)
                val response = withTimeout(8_000) {
                    JsonClient(database = db).load(server.url) { JolpicaAdapter.standings(it, false, 2025) }
                }
                assertEquals(expected, response.data)
                assertEquals(oldTime, response.fetchedUtc)
                assertTrue(response.cached)
                assertNotNull(response.error)
                store.writeStandings(2025, "driver", response.data, response.fetchedUtc)
                assertEquals(expected to oldTime, store.f1Standings(2025, "driver", expected.round))
                withContext(Dispatchers.IO) {
                    val snapshot = db.localData().snapshot(server.url, "network")!!
                    assertEquals(body, snapshot.body)
                    assertEquals(oldTime, snapshot.fetchedUtc)
                }
            } finally { db.close() }
            await(server.finished)
        }
    }

    @Test fun f1CalendarUsesTheSameRealSocketCancellationAndValidatedResponsePath() = runBlocking {
        val peerClosed = AtomicBoolean(false)
        Server { socket -> peerClosed.set(socket.getInputStream().read() == -1) }.use { server ->
            val request = launch(Dispatchers.Default) {
                JsonClient().load(server.url) { F1Api.parseSeason(it, 2025) }
            }
            await(server.requested)
            withTimeout(2_000) { request.cancelAndJoin() }
            await(server.finished)
            assertTrue(peerClosed.get())
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val body = SportsArchive(context).response("https://api.jolpi.ca/ergast/f1/2025.json?limit=100")!!.first
        Server { socket ->
            val bytes = body.toByteArray()
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
                write(bytes)
                flush()
            }
        }.use { server ->
            assertEquals(F1Api.parseSeason(body, 2025),
                JsonClient().load(server.url) { F1Api.parseSeason(it, 2025) }.data)
            await(server.finished)
        }
    }

    @Test fun realHttpErrorsRemainProviderErrors() = runBlocking {
        for (code in listOf(429, 500)) {
            Server { it.getOutputStream().headers(code) }.use { server ->
                try {
                    JsonClient().load(server.url) { it }
                    fail("Expected HTTP $code")
                } catch (error: SportsHttpException) { assertEquals(code, error.status) }
                await(server.finished)
            }
        }
    }
}
