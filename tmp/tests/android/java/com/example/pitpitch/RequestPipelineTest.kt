package com.example.pitpitch

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.JsonClient
import com.example.pitpitch.data.RemoteSnapshot
import com.example.pitpitch.data.db.PitPitchDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RequestPipelineTest {
    private fun database() = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext, PitPitchDatabase::class.java).build()

    @Test fun nextTransferCanFinishWhilePreviousResponseIsStillParsing() = runBlocking {
        val db = database()
        val releaseParse = CountDownLatch(1)
        var first: Deferred<RemoteSnapshot<String>>? = null
        var second: Deferred<RemoteSnapshot<String>>? = null
        try {
            val slots = Semaphore(1)
            val parsing = CompletableDeferred<Unit>()
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            val client = JsonClient(database = db, transport = { url ->
                val current = active.incrementAndGet()
                maximum.updateAndGet { maxOf(it, current) }
                try { url.substringAfterLast('/') } finally { active.decrementAndGet() }
            })
            first = async(Dispatchers.Default) {
                client.loadWithSlots("https://pipeline.test/first", slots) { body ->
                    parsing.complete(Unit)
                    check(releaseParse.await(10, TimeUnit.SECONDS))
                    body
                }
            }
            withTimeout(5000) { parsing.await() }
            second = async(Dispatchers.Default) { client.loadWithSlots("https://pipeline.test/second", slots) { it } }
            val later = withTimeout(5000) { second.await() }
            assertEquals("second", later.data)
            assertFalse(first.isCompleted)
            assertNull(db.localData().snapshot("https://pipeline.test/first", "network"))
            assertEquals(1, maximum.get())
            releaseParse.countDown()
            val earlier = withTimeout(5000) { first.await() }
            assertEquals("first", earlier.data)
            assertFalse("Fetch time must precede slow parsing, not be invented at commit", Instant.parse(earlier.fetchedUtc).isAfter(Instant.parse(later.fetchedUtc)))
            assertEquals(earlier.fetchedUtc, db.localData().snapshot("https://pipeline.test/first", "network")!!.fetchedUtc)
        } finally {
            releaseParse.countDown()
            first?.cancelAndJoin(); second?.cancelAndJoin()
            db.close()
        }
    }

    @Test fun cancellingAQueuedTransferDoesNotFetchParseCacheOrLeakAPermit() = runBlocking {
        val db = database()
        val slots = Semaphore(1)
        slots.acquire()
        val requested = AtomicInteger()
        val parsed = AtomicInteger()
        val client = JsonClient(database = db, transport = { requested.incrementAndGet(); "body" })
        val job = launch(Dispatchers.Default) {
            client.loadWithSlots("https://pipeline.test/cancelled", slots) { parsed.incrementAndGet(); it }
        }
        try {
            withTimeout(5000) { job.cancelAndJoin() }
            assertEquals(0, requested.get())
            assertEquals(0, parsed.get())
            assertEquals(0, slots.availablePermits)
            assertNull(db.localData().snapshot("https://pipeline.test/cancelled", "network"))
            slots.release()
            assertEquals("body", client.loadWithSlots("https://pipeline.test/next", slots) { it }.data)
            assertEquals(1, slots.availablePermits)
        } finally {
            job.cancelAndJoin()
            db.close()
        }
    }
}
