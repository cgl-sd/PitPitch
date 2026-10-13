package com.example.pitpitch

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.*
import com.example.pitpitch.data.db.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SeederRecoveryTest {
    @Test fun failedUpgradeSeedRemainsRetryableAndUsesSelectedRosterClock() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PitPitchDatabase::class.java).build()
        try {
            LocalDataImporter(context, db).importIfNeeded()
            val bundled = SportsArchive(context)
            val rosterUrls = bundled.urls().map { it.first }.filter {
                "/soccer/eng.1/teams/" in it && "/roster?season=2024" in it
            }.take(3)
            assertEquals(3, rosterUrls.size)
            val chosenUrl = rosterUrls[0]
            val faultUrl = rosterUrls[1]
            val corruptUrl = rosterUrls[2]
            // The explicit default port identifies the same real provider URL, but has no APK catalog entry.
            val endpoint = java.net.URI(corruptUrl)
            val unavailableUrl = java.net.URI(endpoint.scheme, endpoint.userInfo, endpoint.host, 443,
                endpoint.path, endpoint.query, endpoint.fragment).toString()
            fun teamId(url: String) = url.substringAfter("/teams/").substringBefore('/').toInt() + 1_000_000
            val raw = bundled.response(chosenUrl)!!.first
            val squad = EspnFootballAdapter.squad(raw, 2024)
            val oldTime = "2026-10-10T00:00:00Z"
            val selectedTime = "2026-10-12T00:00:00Z"
            val store = FootballStore(db)
            // Recreate the previously shipped append-only roster: one departure and one retained player.
            db.players().upsert(squad.players.take(2).map { player ->
                PlayerRow(player.id, 2024, "eng.1", teamId(chosenUrl), "Archive team", player.name, player.position,
                    player.jersey, player.appearances, player.goals, player.assists, player.photoUrl, oldTime)
            })
            val root = JSONObject(raw)
            val athletes = root.getJSONArray("athletes")
            assertTrue(athletes.length() >= 3)
            val newerBody = root.put("athletes", JSONArray().put(athletes.getJSONObject(1)).put(athletes.getJSONObject(2))).toString()
            val selected = EspnFootballAdapter.squad(newerBody, 2024)
            db.localData().putSnapshots(listOf(SourceSnapshotRow(chosenUrl, "network", newerBody, selectedTime,
                LocalDataImporter.sha(newerBody.toByteArray()))))
            val originalCorruptResource = withContext(Dispatchers.IO) { db.localData().snapshot(corruptUrl, "bundled")!! }
            val invalidBody = "{broken archive resource"
            db.localData().putSnapshots(listOf(originalCorruptResource.copy(url = unavailableUrl, body = invalidBody,
                sha256 = LocalDataImporter.sha(invalidBody.toByteArray()))))
            // Old releases could record completion despite failures; upgrading must not skip that domain pass.
            db.meta().put(MetaRow("seedVersion", PitPitchDatabase.SEED_VERSION))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_seed_roster BEFORE INSERT ON players WHEN NEW.teamId = ${teamId(faultUrl)} AND NEW.season = 2024 BEGIN SELECT RAISE(ABORT, 'seed roster fault'); END")
            val archive = SportsArchive(context, db)
            val client = JsonClient(database = db, archive = archive, transport = { error("Seeding must be offline") })
            val seeder = DatabaseSeeder(db, store, archive, client)
            seeder.seedIfNeeded()
            assertNotEquals(DatabaseSeeder.SEED_VERSION, db.meta().get("seedVersion"))
            assertTrue(db.meta().get("seedFailures")!!.toInt() >= 2)
            assertTrue(db.players().byTeam("eng.1", 2024, teamId(faultUrl)).isEmpty())
            assertNull(withContext(Dispatchers.IO) { archive.read(unavailableUrl) { EspnFootballAdapter.squad(it, 2024) } })
            val effective = store.squad("eng.1", 2024, teamId(chosenUrl))!!
            assertEquals(selected.players.map { it.id }.toSet(), effective.first.players.map { it.id }.toSet())
            assertEquals(selectedTime, effective.second)
            assertEquals(selectedTime, store.sourceTime(chosenUrl))
            assertEquals(selectedTime, store.sourceTime("squad:eng.1:${teamId(chosenUrl)}:2024"))

            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_seed_roster")
            db.localData().putSnapshots(listOf(originalCorruptResource.copy(url = unavailableUrl)))
            seeder.seedIfNeeded()
            assertEquals(DatabaseSeeder.SEED_VERSION, db.meta().get("seedVersion"))
            assertEquals("0", db.meta().get("seedFailures"))
            // Reconcile the repaired resources with their real archive membership, not a sentinel/default row.
            for (url in listOf(faultUrl, corruptUrl)) {
                val expected = bundled.read(url) { EspnFootballAdapter.squad(it, 2024) }!!.data
                assertEquals(expected.players.map { it.id }.toSet(),
                    store.squad("eng.1", 2024, teamId(url))!!.first.players.map { it.id }.toSet())
            }
            assertEquals(14659, (2020..2026).sumOf { store.seasonMatches(it).size })
            assertTrue(db.f1().raceCount() >= 150)
            val allTeams = db.players().bySeason("eng.1", 2024)
            seeder.seedIfNeeded()
            assertEquals(allTeams, db.players().bySeason("eng.1", 2024))
            assertEquals(selectedTime, store.squad("eng.1", 2024, teamId(chosenUrl))!!.second)
        } finally { db.close() }
    }
}
