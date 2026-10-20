package com.example.pitpitch

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pitpitch.feature.MatchDetail
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.app.SportsViewModel
import com.example.pitpitch.data.*
import com.example.pitpitch.data.db.*
import com.example.pitpitch.data.repository.*
import com.example.pitpitch.data.local.UserStore
import com.example.pitpitch.data.local.createUserPreferencesStore
import com.example.pitpitch.feature.runHomeClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real Room facts with controlled query/clock boundaries; never touches the installed user database. */
class StateLifecycleRegressionTest {
    @get:Rule val rule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private val year get() = SportsViewModel.State().footballSeason
    private val timestamp = "2026-01-01T00:00:00Z"
    private fun fixture(name: String) = instrumentation.context.assets.open(name).bufferedReader().use { it.readText() }
    private fun race(season: Int) = F1Race(season, 1, "Fixture Grand Prix", "Fixture Circuit", LocalDate.now().plusDays(1).toString(), "14:00:00Z", "UK")
    private fun pollingRace(season: Int): F1Race {
        val start = Instant.now().plusSeconds(3600)
        val date = start.atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
        val time = start.toString().substringAfter('T').substringBefore('.')
        return race(season).copy(date = date, utcTime = time)
    }
    private fun match(season: Int) = FootballMatch("fixture-$season", "英超", "常规联赛", "Home", "Away", "Home", "Away", "1 : 0", "完场", "Track", "$season-09-19", "$season-09-19T11:30:00Z", isDemo = false, homeTeamId = 1000359, awayTeamId = 1000362, competitionId = "eng.1")
    private fun database(calendarTimestamp: String = timestamp, query: (String, List<Any?>) -> Unit = { _, _ -> }): PitPitchDatabase {
        val db = Room.inMemoryDatabaseBuilder(app, PitPitchDatabase::class.java)
            .setQueryCallback({ sql, args -> query(sql, args) }, Executor { it.run() }).build()
        runBlocking {
            val revision = app.assets.open("local-backend-revision.txt").bufferedReader().use { it.readText().trim() }
            for ((key, value) in mapOf("localDataRevision" to revision, "seedDataRevision" to revision, "seedVersion" to DatabaseSeeder.SEED_VERSION,
                "completeF1ResultsVersion" to "2", "f1MediaBindingVersion" to "1",
                "legacyHttpImported" to "1", "legacyCalendarImported" to "1")) db.meta().put(MetaRow(key, value))
            val store = FootballStore(db)
            for (season in listOf(2020, year).distinct()) store.writeMatches(listOf(match(season)), "eng.1", "英超", season, timestamp)
            for (season in listOf(2020, SportsViewModel.State().season).distinct()) store.writeRaces(season, listOf(race(season)), calendarTimestamp)
            db.players().upsert(listOf(
                PlayerRow("A", year, "eng.1", 1000359, "Home", "A scorer", "FW", "9", "8", "7", "3", null, timestamp),
                PlayerRow("B", year, "ita.1", 1000362, "Away", "B scorer", "FW", "10", "6", "5", "4", null, timestamp)))
        }
        return db
    }
    private fun retainedJobs(model: SportsViewModel, field: String): Int {
        val member = SportsViewModel::class.java.getDeclaredField(field)
        member.isAccessible = true
        return (member.get(model) as Map<*, *>).size
    }
    private fun model(db: PitPitchDatabase, owner: ViewModelStore, handle: SavedStateHandle = SavedStateHandle(),
        transport: (String) -> String = { throw SocketTimeoutException("offline fixture") },
        repository: RaceRepository = object : RaceRepository { override suspend fun season(year: Int, force: Boolean): List<F1Race> = throw SocketTimeoutException("offline calendar") },
        wait: suspend (Long) -> Unit = { delay(it) }, store: UserStore = UserStore(app)): SportsViewModel {
        lateinit var model: SportsViewModel
        rule.runOnUiThread {
            model = SportsViewModel(app, JsonClient(transport = transport, archive = SportsArchive(app, db), database = db), repository,
                db = db, savedStateHandle = handle, backend = LocalBackendRepository(app, db), pollingDelay = wait, store = store)
            owner.put("state", model)
        }
        rule.waitUntil(20000) {
            val state = model.state.value
            state.ready && !state.loading && !state.footballLoading &&
                state.calendars[state.season]?.isNotEmpty() == true && state.updated != null &&
                state.matches.isNotEmpty() && state.footballUpdated != null
        }
        return model
    }

    @Test fun offlineFallbackDatabaseFailureKeepsInMemoryCalendarAndOriginalNetworkError() {
        val entered = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        val season = SportsViewModel.State().season
        val bundled = SportsArchive(app).read("https://api.jolpi.ca/ergast/f1/$season.json?limit=100") { F1Api.parseSeason(it, season) }!!
        val memoryTime = Instant.parse(bundled.fetchedUtc).plusSeconds(60).toString()
        val db = database(calendarTimestamp = memoryTime)
        val owner = ViewModelStore()
        try {
            val model = model(db, owner, repository = object : RaceRepository {
                override suspend fun season(year: Int, force: Boolean): List<F1Race> {
                    entered.countDown(); release.await(); throw SocketTimeoutException("offline calendar")
                }
            })
            val before = model.state.value
            assertEquals(memoryTime, before.updated)
            assertEquals(listOf(race(season)), before.races)
            rule.runOnUiThread { model.refresh() }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            db.openHelper.writableDatabase.execSQL("DROP TABLE f1Races")
            release.complete(Unit)
            rule.waitUntil(10000) { !model.state.value.loading && model.state.value.error != null }
            assertEquals(before.calendars, model.state.value.calendars)
            assertEquals(before.updated, model.state.value.updated)
            assertEquals(memoryTime, model.updated(season))
            assertEquals(readableError(SocketTimeoutException("offline calendar")), model.state.value.error)
            assertNotNull(model.state.value.storageError)
            assertTrue(model.state.value.cached)
        } finally { release.complete(Unit); rule.runOnUiThread { owner.clear() }; db.close() }
    }

    @Test fun offlineFallbackDatabaseFailureAcceptsNewerPackagedCalendarWithoutRestampingIt() {
        val entered = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        val season = SportsViewModel.State().season
        val bundled = SportsArchive(app).read("https://api.jolpi.ca/ergast/f1/$season.json?limit=100") { F1Api.parseSeason(it, season) }!!
        val memoryTime = Instant.parse(bundled.fetchedUtc).minusSeconds(60).toString()
        val db = database(calendarTimestamp = memoryTime)
        val owner = ViewModelStore()
        try {
            val model = model(db, owner, repository = object : RaceRepository {
                override suspend fun season(year: Int, force: Boolean): List<F1Race> {
                    entered.countDown(); release.await(); throw SocketTimeoutException("offline calendar")
                }
            })
            assertEquals(listOf(race(season)), model.state.value.races)
            assertEquals(memoryTime, model.state.value.updated)
            rule.runOnUiThread { model.refresh() }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            db.openHelper.writableDatabase.execSQL("DROP TABLE f1Races")
            release.complete(Unit)
            rule.waitUntil(10000) { !model.state.value.loading && model.state.value.error != null }
            assertEquals(bundled.data, model.state.value.races)
            assertEquals(bundled.fetchedUtc, model.state.value.updated)
            assertEquals(bundled.fetchedUtc, model.updated(season))
            assertEquals(readableError(SocketTimeoutException("offline calendar")), model.state.value.error)
            assertNotNull(model.state.value.storageError)
            assertTrue(model.state.value.cached)
        } finally { release.complete(Unit); rule.runOnUiThread { owner.clear() }; db.close() }
    }

    @Test fun ordinaryPreferenceIOExceptionDoesNotBlockRealRoomFootballOrEraseTheFile() = runBlocking {
        val directory = File(app.noBackupFilesDir, "StatePreferences-${UUID.randomUUID()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = database()
        val owner = ViewModelStore()
        try {
            val bytes = "retain original preference parent".toByteArray()
            val parent = File(directory, "not-a-directory").apply { writeBytes(bytes) }
            val diagnostics = File(directory, "diagnostics")
            val preferences = createUserPreferencesStore(File(parent, "user.preferences_pb"), diagnostics, scope)
            val model = model(db, owner, store = UserStore(app, preferences))
            assertPublishedFootball(model)
            assertNotNull(model.state.value.storageError)
            assertTrue(model.state.value.storageError!!.contains("偏好读取失败"))
            assertFalse(model.state.value.footballLoading)
            assertEquals(timestamp, model.state.value.footballUpdated)
            try { preferences.data.first(); fail("Ordinary I/O became a successful empty preference read") }
            catch (_: IOException) { }
            assertArrayEquals(bytes, parent.readBytes())
            assertFalse(diagnostics.exists())
        } finally {
            rule.runOnUiThread { owner.clear() }
            db.close()
            scope.coroutineContext[Job]!!.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    @Test fun healthyPreferenceCollectionAndViewModelSavesRemainActiveAfterIndependentFootballStartup() = runBlocking {
        val directory = File(app.noBackupFilesDir, "StatePreferences-${UUID.randomUUID()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = database()
        val owner = ViewModelStore()
        try {
            val diagnostics = File(directory, "diagnostics")
            val preferences = createUserPreferencesStore(File(directory, "user.preferences_pb"), diagnostics, scope)
            val model = model(db, owner, store = UserStore(app, preferences))
            preferences.edit {
                it[booleanPreferencesKey("utc")] = true
                it[stringSetPreferencesKey("favorites")] = setOf("initial")
            }
            rule.waitUntil(10000) { model.state.value.utc && model.state.value.favorites == setOf("initial") }
            rule.runOnUiThread { model.utc(false); model.toggle("saved"); model.followCompetition("eng.1") }
            rule.waitUntil(10000) {
                val state = model.state.value
                !state.utc && state.favorites == setOf("initial", "saved") && "eng.1" in state.followedCompetitions
            }
            val persisted = preferences.data.first()
            assertEquals(false, persisted[booleanPreferencesKey("utc")])
            assertEquals(setOf("initial", "saved"), persisted[stringSetPreferencesKey("favorites")])
            assertEquals(setOf("eng.1"), persisted[stringSetPreferencesKey("followed_competitions")])
            assertPublishedFootball(model)
            assertNull(model.state.value.storageError)
            assertFalse(diagnostics.exists())
        } finally {
            rule.runOnUiThread { owner.clear() }
            db.close()
            scope.coroutineContext[Job]!!.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    private fun assertPublishedFootball(model: SportsViewModel) {
        val expected = match(year)
        val actual = model.state.value.matches.single()
        assertEquals(expected.id, actual.id)
        assertEquals(expected.competitionId, actual.competitionId)
        assertEquals(expected.homeTeamId, actual.homeTeamId)
        assertEquals(expected.awayTeamId, actual.awayTeamId)
        assertEquals(expected.home, actual.home)
        assertEquals(expected.away, actual.away)
        assertEquals(expected.score, actual.score)
        assertEquals(expected.status, actual.status)
        assertEquals(expected.kickoffUtc, actual.kickoffUtc)
        assertEquals(expected.date, actual.date)
        assertFalse(actual.isDemo)
    }

    /** The first screen asks for one background refresh; the state holder never fetches on its own. */
    @Test fun startupRefreshHappensOnceWhenTheHostAsksAfterLocalDataIsPrimed() {
        val db = database()
        val owner = ViewModelStore()
        val requests = AtomicInteger()
        val races = AtomicInteger()
        try {
            val model = model(db, owner,
                transport = { requests.incrementAndGet(); throw SocketTimeoutException("offline fixture") },
                repository = object : RaceRepository {
                    override suspend fun season(year: Int, force: Boolean): List<F1Race> {
                        races.incrementAndGet()
                        throw SocketTimeoutException("offline calendar")
                    }
                })
            assertTrue(model.state.value.homeReady)
            val baseRaces = races.get()
            val baseRequests = requests.get()
            rule.runOnUiThread { model.refreshOnStartupIfNeeded() }
            rule.waitUntil(10000) { races.get() > baseRaces && requests.get() > baseRequests }
            val refreshedRaces = races.get()
            rule.runOnUiThread { model.refreshOnStartupIfNeeded(); model.refreshOnStartupIfNeeded() }
            rule.waitUntil(10000) { !model.state.value.loading && !model.state.value.footballLoading && !model.state.value.rankingLoading }
            assertEquals(refreshedRaces, races.get())
        } finally {
            rule.runOnUiThread { owner.clear() }
            db.close()
        }
    }

    @Test fun suspendedALeaderboardDoesNotDropBAndCancelledReplacementKeepsLoadingIdentity() {
        val arm = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val db = database { sql, args ->
            if (sql.startsWith("SELECT * FROM players WHERE competitionId") && args.firstOrNull() == "eng.1" && arm.compareAndSet(true, false)) {
                entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
            }
        }
        val owner = ViewModelStore()
        try {
            val model = model(db, owner)
            arm.set(true)
            rule.runOnUiThread { model.loadLeaderboards() }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            rule.runOnUiThread { model.footballCompetition("ita.1"); model.loadLeaderboards() }
            rule.waitUntil(10000) { model.state.value.leaderboards["$year|ita.1"]?.data?.any { it.name == "B scorer" && it.goals == 5 } == true }
            assertTrue("A still has its own loading owner", "$year|eng.1" in model.state.value.leaderboardLoading)
            rule.runOnUiThread { model.footballCompetition("eng.1"); model.loadLeaderboards(true) }
            release.countDown()
            rule.waitUntil(10000) { model.state.value.leaderboardLoading.isEmpty() && model.state.value.leaderboards["$year|eng.1"]?.data?.any { it.name == "A scorer" && it.goals == 7 } == true }
            assertTrue(model.state.value.leaderboardErrors.isEmpty())
        } finally { release.countDown(); rule.runOnUiThread { owner.clear() }; db.close() }
    }

    @Test fun seasonReadPausedAfterMatchesCannotPublishAfterNewerOverlayAggregate() {
        val arm = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val db = database { sql, args ->
            if (sql.startsWith("SELECT * FROM standings WHERE") && (args.lastOrNull() as? Number)?.toInt() == year && arm.compareAndSet(true, false)) {
                entered.countDown(); check(release.await(30, TimeUnit.SECONDS))
            }
        }
        val owner = ViewModelStore()
        try {
            val league = footballCompetitions.first { it.id == "eng.1" }
            // The fixture is a live match; keep it on the current day so the day-overlay target selection is stable.
            val dayBody = fixture("espn-day-overlay.json").replace("\"2026-09-19", "\"${LocalDate.now()}")
            val id = "fb-espn-eng.1-401879269"
            val store = FootballStore(db)
            runBlocking { store.writeMatches(EspnFootballAdapter.matches(dayBody, league, year).map { it.copy(score = "1 : 0") }, league.id, league.title, year, timestamp) }
            val model = model(db, owner, transport = { url ->
                if ("/scoreboard?dates=" in url) dayBody
                else if (url == EspnFootballAdapter.standingsUrl("eng.1", year)) fixture("espn-standings-six.json")
                else throw SocketTimeoutException("offline fixture")
            })
            arm.set(true)
            rule.runOnUiThread { model.footballSeason(2020); model.footballSeason(year) }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            rule.runOnUiThread { model.refreshDayOverlay(true) }
            rule.waitUntil(10000) { runBlocking { store.seasonMatches(year).first { it.id == id }.score == "2 : 1" } }
            // With the old independent paths B publishes Y before A is released. The shared boundary
            // instead holds B's aggregate until A publishes, so either implementation can be driven
            // to quiescence without cancelling either request or guessing source timestamps.
            runBlocking {
                withTimeoutOrNull(5000) { model.state.first { it.matches.firstOrNull { row -> row.id == id }?.score == "2 : 1" } }
            }
            release.countDown()
            rule.waitUntil(10000) { model.state.value.matches.firstOrNull { it.id == id }?.score == "2 : 1" && !model.state.value.footballLoading }
            assertEquals("2 : 1", runBlocking { store.seasonMatches(year).first { it.id == id }.score })
            assertEquals("进行中", model.state.value.matches.first { it.id == id }.status)
        } finally { release.countDown(); rule.runOnUiThread { owner.clear() }; db.close() }
    }

    @Test fun boundedDetailsKeepPinnedFactsAndRecoverEvictedFactsFromRoomWithoutNetwork() {
        val db = database()
        val owner = ViewModelStore()
        val requests = AtomicInteger()
        val matches = (0 until 160).map { index ->
            val kickoff = Instant.parse("$year-01-01T00:00:00Z").plusSeconds(index.toLong())
            match(year).copy(id = "fb-espn-eng.1-${900000 + index}", date = "$year-01-01", kickoffUtc = kickoff.toString())
        }
        fun detail(index: Int) = FootballDetail(emptyList(),
            listOf(MatchStatistic("控球率", "67%", "33%"), MatchStatistic("射门", (index + 7).toString(), "4")))
        try {
            val store = FootballStore(db)
            runBlocking {
                store.writeMatches(matches, "eng.1", "英超", year, timestamp)
                for ((index, match) in matches.withIndex()) store.writeDetail(match.id, detail(index), timestamp)
            }
            val persisted = runBlocking { matches.map { checkNotNull(store.detail(it)) } }
            for (index in persisted.indices) assertEquals(detail(index).statistics.toSet(), persisted[index].statistics.toSet())
            val model = model(db, owner, transport = { requests.incrementAndGet(); throw SocketTimeoutException("unexpected network") })
            val displayed = mutableStateOf(matches.first())
            rule.setContent {
                val match = displayed.value
                val state by model.state.collectAsStateWithLifecycle()
                DisposableEffect(match.id) {
                    model.pinMatchDetail(match.id)
                    onDispose { model.unpinMatchDetail(match.id) }
                }
                LaunchedEffect(match.id) { model.matchDetail(match) }
                MaterialTheme {
                    MatchDetail(match, ZoneOffset.UTC, null, {}, detail = state.footballDetails[match.id],
                        loading = match.id in state.detailLoading, error = state.detailErrors[match.id])
                }
            }
            rule.waitUntil(10000) { model.state.value.footballDetails[matches[0].id]?.data == persisted[0] }
            for (index in 1 until matches.size) {
                rule.runOnUiThread {
                    model.matchDetail(matches[index])
                    // A genuine cache hit is also a visit: it must move this otherwise-old detail forward.
                    if (index == 128) model.matchDetail(matches[1])
                }
                rule.waitUntil(10000) { matches[index].id !in model.state.value.detailLoading && model.state.value.footballDetails[matches[index].id]?.data == persisted[index] }
            }
            assertTrue(model.state.value.footballDetails.size <= 128 + 1 + 12)
            assertEquals(persisted[0], model.state.value.footballDetails[matches[0].id]!!.data)
            assertEquals(persisted[1], model.state.value.footballDetails[matches[1].id]!!.data)
            assertFalse(model.state.value.footballDetails.containsKey(matches[2].id))
            assertEquals(0, retainedJobs(model, "detailJobs"))
            rule.onNode(hasText("7") and hasAnyAncestor(hasTestTag("statistic-射门")), useUnmergedTree = true).assertIsDisplayed()
            // Keep the outgoing destination alive as NavHost does during its exit animation.
            rule.runOnUiThread { model.pinMatchDetail(matches[0].id); displayed.value = matches[2] }
            rule.waitUntil(10000) { model.state.value.footballDetails[matches[2].id]?.data == persisted[2] && model.state.value.detailLoading.isEmpty() }
            rule.onNode(hasText("9") and hasAnyAncestor(hasTestTag("statistic-射门")), useUnmergedTree = true).assertIsDisplayed()
            assertEquals(timestamp, model.state.value.footballDetails[matches[2].id]!!.fetchedUtc)
            assertEquals(persisted[0], model.state.value.footballDetails[matches[0].id]!!.data)
            assertEquals(persisted[2], runBlocking { store.detail(matches[2]) })
            assertEquals(0, requests.get())
            rule.runOnUiThread { model.unpinMatchDetail(matches[0].id); model.squadFor(year, "eng.1", 1000359) }
            rule.waitUntil(10000) { model.state.value.squadLoading.isEmpty() && model.state.value.squads["$year|eng.1|1000359"] != null }
            assertEquals("7", model.state.value.squads["$year|eng.1|1000359"]!!.data.players.single().goals)
            assertEquals(0, retainedJobs(model, "detailJobs"))
            assertEquals(0, retainedJobs(model, "squadJobs"))
        } finally { rule.runOnUiThread { owner.clear() }; db.close() }
    }

    @Test fun newViewModelRestoresLightweightSelectionAndRouteReadsDoNotChangeHomepage() {
        val db = database()
        val firstOwner = ViewModelStore()
        val secondOwner = ViewModelStore()
        try {
            val handle = SavedStateHandle()
            val first = model(db, firstOwner, handle)
            rule.runOnUiThread { first.season(2020); first.footballSeason(2020); first.footballCompetition("ita.1") }
            rule.waitUntil(10000) { !first.state.value.loading && !first.state.value.footballLoading }
            val restoredValues = handle.keys().associateWith { handle.get<Any?>(it) }
            assertTrue(restoredValues.values.all { it is Int || it is String })
            rule.runOnUiThread { firstOwner.clear() }
            val restored = model(db, secondOwner, SavedStateHandle(restoredValues))
            assertNotSame(first, restored)
            assertEquals(2020, restored.state.value.season)
            assertEquals(2020, restored.state.value.footballSeason)
            assertEquals("ita.1", restored.state.value.footballCompetition)
            assertEquals("fixture-2020", restored.state.value.matches.single().id)
            assertEquals("f1-2020-1", restored.state.value.races.single().id)
            rule.runOnUiThread { restored.loadFootballRoute(year); restored.loadRaceRoute(race(SportsViewModel.State().season).id) }
            rule.waitUntil(10000) { year in restored.state.value.footballSeasons && restored.state.value.allRaces.any { it.season == SportsViewModel.State().season } }
            assertEquals("fixture-$year", restored.state.value.footballSeasons[year]!!.matches.single().id)
            assertEquals(2020, restored.state.value.footballSeason)
            assertEquals("ita.1", restored.state.value.footballCompetition)
        } finally { rule.runOnUiThread { firstOwner.clear(); secondOwner.clear() }; db.close() }
    }

    @Test fun malformedIntentDefaultsCannotCrashViewModelConstruction() {
        val db = database()
        val owner = ViewModelStore()
        try {
            val model = model(db, owner, SavedStateHandle(mapOf(
                "f1Year" to "not-a-year", "footballYear" to 1.5, "competition" to 7)))
            assertEquals(SportsViewModel.State().season, model.state.value.season)
            assertEquals(SportsViewModel.State().footballSeason, model.state.value.footballSeason)
            assertEquals("eng.1", model.state.value.footballCompetition)
        } finally { rule.runOnUiThread { owner.clear() }; db.close() }
    }

    @Test fun racePollingPausesResumesOnlyActiveDetailAndDisposeClearsTarget() = runBlocking {
        val db = database()
        val owner = ViewModelStore()
        val starts = Channel<Unit>(Channel.UNLIMITED)
        val ticks = Channel<Unit>(Channel.UNLIMITED)
        val resultCalls = AtomicInteger()
        val cancelled = AtomicInteger()
        try {
            val model = model(db, owner, transport = { url ->
                if ("/results.json" in url) resultCalls.incrementAndGet()
                throw SocketTimeoutException("offline poll")
            }, wait = { duration ->
                if (duration == 120_000L) {
                    starts.send(Unit)
                    try { ticks.receive() } finally { cancelled.incrementAndGet() }
                } else awaitCancellation()
            })
            val race = pollingRace(SportsViewModel.State().season)
            rule.runOnUiThread { model.startRacePolling(race) }
            withTimeout(10000) { starts.receive() }
            rule.runOnUiThread { model.pauseForegroundPolling() }
            rule.waitUntil(10000) { cancelled.get() == 1 }
            assertEquals(0, resultCalls.get())
            rule.runOnUiThread { model.resumeForegroundPolling(); model.resumeForegroundPolling() }
            withTimeout(10000) { starts.receive() }
            assertTrue(starts.tryReceive().isFailure)
            ticks.send(Unit)
            rule.waitUntil(10000) { resultCalls.get() == 1 }
            withTimeout(10000) { starts.receive() }
            rule.runOnUiThread { model.stopRacePolling(race.id); model.pauseForegroundPolling(); model.resumeForegroundPolling() }
            rule.waitUntil(10000) { cancelled.get() == 3 }
            assertTrue(starts.tryReceive().isFailure)
            assertEquals(1, resultCalls.get())
            rule.runOnUiThread { model.startRacePolling(race) }
            withTimeout(10000) { starts.receive() }
            rule.runOnUiThread { model.startRacePolling(race.copy(season = 2020)); model.pauseForegroundPolling(); model.resumeForegroundPolling() }
            rule.waitUntil(10000) { cancelled.get() == 4 }
            assertTrue(starts.tryReceive().isFailure)
            rule.waitUntil(10000) { model.state.value.resultLoading.isEmpty() && model.state.value.qualifyingLoading.isEmpty() }
            assertEquals(0, retainedJobs(model, "resultJobs"))
            assertEquals(0, retainedJobs(model, "qualifyingJobs"))
            assertEquals(1, resultCalls.get())
        } finally { rule.runOnUiThread { owner.clear() }; db.close(); starts.close(); ticks.close() }
    }

    @Test fun exitingOldRaceCannotStopTheNewVisibleRacesTicker() = runBlocking {
        val db = database()
        val owner = ViewModelStore()
        val starts = Channel<Unit>(Channel.UNLIMITED)
        val ticks = Channel<Unit>(Channel.UNLIMITED)
        val requests = Channel<String>(Channel.UNLIMITED)
        try {
            val model = model(db, owner, transport = { url ->
                if ("/results.json" in url) requests.trySend(url)
                throw SocketTimeoutException("offline poll")
            }, wait = { duration ->
                if (duration == 120_000L) { starts.send(Unit); ticks.receive() } else awaitCancellation()
            })
            val oldRace = pollingRace(SportsViewModel.State().season)
            val currentRace = oldRace.copy(round = oldRace.round + 1)
            rule.runOnUiThread { model.startRacePolling(oldRace) }
            withTimeout(10000) { starts.receive() }
            rule.runOnUiThread { model.startRacePolling(currentRace); model.stopRacePolling(oldRace.id) }
            withTimeout(10000) { starts.receive() }
            ticks.send(Unit)
            val requested = withTimeout(10000) { requests.receive() }
            assertTrue(requested.contains("/${currentRace.season}/${currentRace.round}/results.json"))
            withTimeout(10000) { starts.receive() }
            rule.runOnUiThread { model.stopRacePolling(currentRace.id); model.pauseForegroundPolling(); model.resumeForegroundPolling() }
            rule.waitUntil(10000) { model.state.value.resultLoading.isEmpty() && model.state.value.qualifyingLoading.isEmpty() }
            assertTrue(starts.tryReceive().isFailure)
        } finally { rule.runOnUiThread { owner.clear() }; db.close(); starts.close(); ticks.close(); requests.close() }
    }

    @Test fun homeClockStopsWhileNotStartedAndResumesWithImmediateCurrentTime() = runBlocking {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val now = AtomicReference(Instant.parse("2026-10-12T12:00:00Z"))
        val reads = AtomicInteger()
        val cancelled = AtomicInteger()
        val ticks = Channel<Unit>(Channel.UNLIMITED)
        val observed = Channel<Pair<Instant, Long>>(Channel.UNLIMITED)
        val kickoff = match(year).copy(kickoffUtc = "2026-10-12T12:00:10Z")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            rule.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
            scope.launch {
                runHomeClock(owner.lifecycle, listOf(kickoff), emptyList(), ZoneOffset.UTC,
                    onTick = {}, clock = { reads.incrementAndGet(); now.get() }, wait = { duration ->
                        observed.send(now.get() to duration)
                        try { ticks.receive() } finally { cancelled.incrementAndGet() }
                    })
            }
            rule.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
            assertEquals(10001L, withTimeout(10000) { observed.receive() }.second)
            rule.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
            rule.waitUntil(10000) { cancelled.get() == 1 }
            now.set(Instant.parse("2026-10-12T12:03:00Z"))
            assertEquals(1, reads.get())
            assertTrue(observed.tryReceive().isFailure)
            rule.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
            assertEquals(now.get() to 60000L, withTimeout(10000) { observed.receive() })
            assertEquals(2, reads.get())
        } finally {
            rule.runOnUiThread { owner.registry.currentState = Lifecycle.State.DESTROYED; scope.cancel() }
            ticks.close(); observed.close()
        }
    }
}
