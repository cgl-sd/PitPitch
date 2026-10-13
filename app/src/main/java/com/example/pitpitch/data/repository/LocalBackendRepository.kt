package com.example.pitpitch.data.repository

import android.content.Context
import com.example.pitpitch.PitPitchApplication
import com.example.pitpitch.data.db.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Hot display metadata is small and immutable; list rows never execute SQL or parse asset files. */
data class LocalReferences(val f1Media: JSONObject = JSONObject(), val names: JSONObject = JSONObject(),
    val badges: Map<String, String> = emptyMap(), val legacyBadges: Map<String, String> = emptyMap(), val ready: Boolean = false,
    val teamColors: JSONObject = JSONObject(), val teamBadges: Map<Int, String> = emptyMap())
class LocalBackendRepository(private val context: Context, private val db: PitPitchDatabase) {
    private val lock = Mutex()
    private val mutable = MutableStateFlow(LocalReferences())
    val references = mutable.asStateFlow()
    suspend fun prepare() = withContext(Dispatchers.IO) {
        if (mutable.value.ready) return@withContext
        lock.withLock {
            if (mutable.value.ready) return@withLock
            val start = System.nanoTime()
            LocalDataImporter(context, db).importIfNeeded()
            val imported = System.nanoTime()
            val dao = db.localData()
            val assets = dao.allAssets()
            val f1 = JSONObject()
            assets.filter { it.kind in setOf("layout", "circuit", "driver", "logo", "car") }
                .forEach { f1.put(it.resourceKey, JSONObject(it.metadataJson)) }
            fun documentJson(path: String): JSONObject {
                var readFailure: Exception? = null
                try {
                    dao.documentBody(path)?.let { return JSONObject(it) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    readFailure = error
                }
                // These reference documents are immutable APK inputs, not another cache layer.
                return try {
                    context.assets.open(path).bufferedReader().use { JSONObject(it.readText()) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    readFailure?.let { error.addSuppressed(it) }
                    throw error
                }
            }
            val names = documentJson("football-chinese-names.json")
            val colors = documentJson("football-team-colors.json")
            val badges = assets.filter { it.kind == "badge" }
            val identities = badges.flatMap { row ->
                val teams = JSONObject(row.metadataJson).optJSONObject("teams") ?: JSONObject()
                teams.keys().asSequence().mapNotNull { it.toIntOrNull()?.let { id -> (id + 1_000_000) to row.assetPath } }.toList()
            }.groupBy({ it.first }, { it.second }).mapNotNull { (id, paths) -> paths.distinct().singleOrNull()?.let { id to it } }.toMap()
            mutable.value = LocalReferences(f1, names, badges.associate { it.sourceUrl!! to it.assetPath },
                assets.filter { it.kind == "legacyBadge" }.associate { it.entityId!! to it.assetPath }, true, colors, identities)
            android.util.Log.i("PitPitchBackend", "importMs=${(imported-start)/1_000_000} preloadMs=${(System.nanoTime()-imported)/1_000_000}")
        }
    }
}
fun localBackend(context: Context): LocalBackendRepository = (context.applicationContext as PitPitchApplication).localBackend
