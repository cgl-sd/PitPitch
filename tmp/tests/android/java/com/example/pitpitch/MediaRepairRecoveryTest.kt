package com.example.pitpitch

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.*
import com.example.pitpitch.data.db.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class MediaRepairRecoveryTest {
    @Test fun unreadableStandingResourceDoesNotRollBackValidIdentityRepairsAndRemainsRetryable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PitPitchDatabase::class.java).build()
        try {
            LocalDataImporter(context, db).importIfNeeded()
            val url = "https://api.jolpi.ca/ergast/f1/2020/driverstandings.json?limit=100"
            val original = withContext(Dispatchers.IO) { db.localData().snapshot(url, "bundled")!! }
            val rankings = JolpicaAdapter.standings(original.body, false, 2020)
            val store = FootballStore(db)
            store.writeStandings(2020, "driver", rankings.copy(entries = rankings.entries.map { it.copy(driverId = null, constructorId = null) }), original.fetchedUtc)
            val facts = store.f1Standings(2020, "driver", null)!!.first.entries.map { Triple(it.position, it.name, it.points) }
            val parsed = URI(url)
            val unavailable = URI(parsed.scheme, parsed.userInfo, parsed.host, 443, parsed.path, parsed.query, parsed.fragment).toString()
            val badBody = "{broken standings resource"
            db.localData().putSnapshots(listOf(original.copy(url = unavailable, body = badBody, sha256 = LocalDataImporter.sha(badBody.toByteArray()))))
            // Already complete sporting data can still need the independent legacy media-identity pass.
            db.meta().put(MetaRow("seedVersion", DatabaseSeeder.SEED_VERSION))
            db.meta().put(MetaRow("seedDataRevision", db.meta().get("localDataRevision")!!))
            db.meta().put(MetaRow("completeF1ResultsVersion", "2"))
            val seeder = DatabaseSeeder(db, store, SportsArchive(context, db))
            seeder.seedIfNeeded()
            assertNull(db.meta().get("f1MediaBindingVersion"))
            assertTrue(db.meta().get("seedFailures")!!.toInt() > 0)
            val repaired = store.f1Standings(2020, "driver", null)!!.first.entries
            assertEquals(rankings.entries.map { it.driverId }, repaired.map { it.driverId })
            assertEquals(rankings.entries.map { it.constructorId }, repaired.map { it.constructorId })
            assertEquals(facts, repaired.map { Triple(it.position, it.name, it.points) })
            assertEquals(original.fetchedUtc, store.f1Standings(2020, "driver", null)!!.second)

            db.localData().putSnapshots(listOf(original.copy(url = unavailable)))
            seeder.seedIfNeeded()
            assertNotNull(db.meta().get("f1MediaBindingVersion"))
            assertEquals(0, db.meta().get("seedFailures")!!.toInt())
            assertEquals(repaired, store.f1Standings(2020, "driver", null)!!.first.entries)
        } finally { db.close() }
    }
}
