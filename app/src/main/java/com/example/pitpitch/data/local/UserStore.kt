package com.example.pitpitch.data.local

import android.content.Context
import com.example.pitpitch.data.FootballTeam
import com.example.pitpitch.data.footballCompetitions
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map

internal val defaultHomeTeam = FootballTeam(1_000_360, "Manchester United", "https://a.espncdn.com/i/teamlogos/soccer/500/360.png")
internal val defaultHiddenHomeCompetitions = footballCompetitions.filter { it.id !in setOf("eng.1", "uefa.champions") }.map { it.id }.toSet()

internal fun storedHomeTeam(prefs: Preferences): FootballTeam? {
    val id = prefs[intPreferencesKey("home_team_id")]
    val name = prefs[stringPreferencesKey("home_team_name")]
    return if (id != null && name != null) FootballTeam(id, name, prefs[stringPreferencesKey("home_team_badge")])
    else if (prefs[booleanPreferencesKey("home_team_choice_initialized")] != true && id == null) defaultHomeTeam else null
}
internal fun storedHiddenHomeCompetitions(prefs: Preferences) = prefs[stringSetPreferencesKey("hidden_home_competitions")] ?: defaultHiddenHomeCompetitions

private fun readablePreferencesFile(file: File): File {
    val path = file.toPath().toAbsolutePath()
    val parent = requireNotNull(path.parent)
    // DataStore treats FileNotFoundException as a missing file, including ENOTDIR/EACCES.
    // Validate the lazy IO path first, so only a genuinely absent file means empty preferences.
    Files.createDirectories(parent)
    if (!Files.isReadable(parent) || !Files.isExecutable(parent)) throw IOException("Cannot read preference directory: $parent")
    val attributes = try { Files.readAttributes(path, BasicFileAttributes::class.java) }
    catch (_: NoSuchFileException) { return file }
    if (!attributes.isRegularFile || !Files.isReadable(path)) throw IOException("Cannot read preference file: $path")
    return file
}

// Recovery is restricted to serializer corruption. Copy failure/ordinary I/O remains an error.
internal fun createUserPreferencesStore(file: File, diagnostics: File, scope: CoroutineScope): DataStore<Preferences> =
    createUserPreferencesStore({ file }, { diagnostics }, scope)

private fun createUserPreferencesStore(produceFile: () -> File, diagnostics: () -> File, scope: CoroutineScope): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { corruption ->
            // PreferencesMapCompat wraps the parse exception once; a nested IO cause is a stream failure.
            // Keep the public cause contract, not a dependency on DataStore's private shaded protobuf API.
            var cause = corruption.cause?.cause
            while (cause != null) {
                if (cause is IOException) throw cause
                cause = cause.cause
            }
            val directory = diagnostics()
            val file = readablePreferencesFile(produceFile())
            Files.createDirectories(directory.toPath())
            file.copyTo(File(directory, "${file.name}.${UUID.randomUUID()}.corrupt"), overwrite = false)
            emptyPreferences()
        },
        scope = scope,
        produceFile = { readablePreferencesFile(produceFile()) },
    )

// Ordinary preferences stay backed up; obsolete calendar files are left untouched.
class UserStore internal constructor(
    context: Context,
    private val store: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context, sharedStore(context))
    private val old = context.getSharedPreferences("pit_pitch_user", 0)
    companion object {
        @Volatile private var shared: DataStore<Preferences>? = null
        private fun sharedStore(context: Context): DataStore<Preferences> = shared ?: synchronized(this) {
            shared ?: run {
                val app = context.applicationContext
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                // Resolving noBackupFilesDir can create directories; defer it to DataStore's IO scope.
                val diagnostics = { File(app.noBackupFilesDir, "preference_diagnostics") }
                createUserPreferencesStore({ app.preferencesDataStoreFile("pit_pitch") }, diagnostics, scope)
            }.also { shared = it }
        }
    }
    private val favorites = stringSetPreferencesKey("favorites")
    private val demo = booleanPreferencesKey("demo")
    private val utc = booleanPreferencesKey("utc")
    private val teamChoice = booleanPreferencesKey("home_team_choice_initialized")
    private val teamId = intPreferencesKey("home_team_id")
    private val teamName = stringPreferencesKey("home_team_name")
    private val teamBadge = stringPreferencesKey("home_team_badge")
    private val migrated = booleanPreferencesKey("migrated")
    private val realDefault = booleanPreferencesKey("real_data_default_v060")
    private val hiddenHome = stringSetPreferencesKey("hidden_home_competitions")
    private val followed = stringSetPreferencesKey("followed_competitions")
    data class Settings(val favorites: Set<String>, val demo: Boolean, val utc: Boolean, val homeTeam: FootballTeam? = defaultHomeTeam, val followedCompetitions: Set<String> = emptySet(), val hiddenHomeCompetitions: Set<String> = defaultHiddenHomeCompetitions)
    val settings = store.data.map { prefs ->
        Settings(prefs[favorites].orEmpty(), prefs[demo] ?: false, prefs[utc] ?: false, storedHomeTeam(prefs),
            prefs[followed].orEmpty(), storedHiddenHomeCompetitions(prefs))
    }
    suspend fun migrate() = store.edit { prefs ->
        if (prefs[hiddenHome] == null) prefs[hiddenHome] = defaultHiddenHomeCompetitions
        if (prefs[teamChoice] != true) {
            if (prefs[teamId] == null) {
                prefs[teamId] = defaultHomeTeam.id
                prefs[teamName] = defaultHomeTeam.name
                prefs[teamBadge] = requireNotNull(defaultHomeTeam.badgeUrl)
            }
            prefs[teamChoice] = true
        }
        if (prefs[realDefault] != true) { prefs[demo] = false; prefs[realDefault] = true }
        if (prefs[migrated] != true) {
            prefs[favorites] = old.getStringSet("favorites", emptySet()).orEmpty().toSet()
            prefs[migrated] = true
        }
    }
    suspend fun toggle(id: String) = store.edit { prefs ->
        val current = prefs[favorites].orEmpty()
        prefs[favorites] = if (id in current) current - id else current + id
    }
    suspend fun homeTeam(team: FootballTeam?) = store.edit {
        it[teamChoice] = true
        if (team == null) { it.remove(teamId); it.remove(teamName); it.remove(teamBadge) }
        else { it[teamId] = team.id; it[teamName] = team.name; if (team.badgeUrl == null) it.remove(teamBadge) else it[teamBadge] = team.badgeUrl }
    }
    suspend fun demo(value: Boolean) = store.edit { it[demo] = value }
    suspend fun followCompetition(id: String) = store.edit {
        val current = it[followed].orEmpty()
        it[followed] = if (id in current) current - id else current + id
    }
    suspend fun homeCompetition(id: String, visible: Boolean) = store.edit { prefs ->
        require(id == "f1" || footballCompetitions.any { it.id == id })
        val hidden = storedHiddenHomeCompetitions(prefs)
        prefs[hiddenHome] = if (visible) hidden - id else hidden + id
    }
    suspend fun utc(value: Boolean) = store.edit { it[utc] = value }
}
