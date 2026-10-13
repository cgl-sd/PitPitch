package com.example.pitpitch.data.db

import androidx.room.*

/** Large artwork remains in immutable files; only identity/provenance is indexed here. */
@Entity(tableName = "localAssets", indices = [Index("kind", "season", "entityId"), Index("sourceUrl")])
data class LocalAssetRow(@PrimaryKey val resourceKey: String, val kind: String, val entityId: String?,
    val season: Int?, val assetPath: String, val sourceUrl: String?, val sha256: String,
    val width: Int?, val height: Int?, val metadataJson: String)
@Entity(tableName = "localDocuments")
data class LocalDocumentRow(@PrimaryKey val resourceKey: String, val body: String, val sha256: String)
@Entity(tableName = "sourceSnapshots", primaryKeys = ["url", "origin"])
data class SourceSnapshotRow(val url: String, val origin: String, val body: String, val fetchedUtc: String, val sha256: String)
data class SnapshotHeader(val url: String, val fetchedUtc: String)
data class SnapshotMetadata(val url: String, val origin: String, val fetchedUtc: String, val sha256: String)
@Entity(tableName = "footballRounds", primaryKeys = ["season", "matchId"], indices = [Index("competitionId", "season")])
data class FootballRoundRow(val season: Int, val matchId: String, val competitionId: String, val homeTeamId: Int,
    val awayTeamId: Int, val providerNote: String, val number: Int, val source: String)

/** Synchronous reads are confined to the IO repository; Room rejects calls on the UI thread. */
@Dao interface LocalDataDao {
    @Upsert suspend fun putAssets(rows: List<LocalAssetRow>)
    @Query("DELETE FROM localAssets") suspend fun clearAssets()
    @Query("SELECT * FROM localAssets WHERE kind = :kind") suspend fun assets(kind: String): List<LocalAssetRow>
    @Query("SELECT * FROM localAssets") suspend fun allAssets(): List<LocalAssetRow>
    @Query("SELECT * FROM localAssets WHERE resourceKey = :key") suspend fun asset(key: String): LocalAssetRow?
    @Upsert suspend fun putDocuments(rows: List<LocalDocumentRow>)
    @Query("DELETE FROM localDocuments") suspend fun clearDocuments()
    @Query("SELECT length(body) FROM localDocuments WHERE resourceKey = :key") fun documentLength(key: String): Int?
    @Query("SELECT substr(body, :offset, :count) FROM localDocuments WHERE resourceKey = :key")
    fun documentPart(key: String, offset: Int, count: Int): String?
    /** Bound each CursorWindow row; SQLite offsets count characters, not UTF-8 bytes. */
    @Transaction fun documentBody(key: String): String? {
        val length = documentLength(key) ?: return null
        return buildString {
            for (offset in 1..length step 131072) append(checkNotNull(documentPart(key, offset, 131072)))
        }
    }
    @Upsert suspend fun putSnapshots(rows: List<SourceSnapshotRow>)
    @Query("SELECT url, origin, fetchedUtc, sha256 FROM sourceSnapshots WHERE url = :url AND origin = :origin")
    fun snapshotMetadata(url: String, origin: String): SnapshotMetadata?
    @Query("SELECT url, origin, fetchedUtc, sha256 FROM sourceSnapshots WHERE url = :url")
    fun snapshotSources(url: String): List<SnapshotMetadata>
    @Query("SELECT length(body) FROM sourceSnapshots WHERE url = :url AND origin = :origin")
    fun snapshotLength(url: String, origin: String): Int?
    @Query("SELECT substr(body, :offset, :count) FROM sourceSnapshots WHERE url = :url AND origin = :origin")
    fun snapshotPart(url: String, origin: String, offset: Int, count: Int): String?
    /** Read one committed version without ever putting the complete TEXT into a CursorWindow. */
    @Transaction fun snapshot(url: String, origin: String): SourceSnapshotRow? {
        val metadata = snapshotMetadata(url, origin) ?: return null
        val length = checkNotNull(snapshotLength(url, origin))
        val body = buildString {
            // SQLite counts Unicode code points; this bounds each part to at most 512 KiB of UTF-8.
            for (offset in 1..length step 131072) append(checkNotNull(snapshotPart(url, origin, offset, 131072)))
        }
        return SourceSnapshotRow(metadata.url, metadata.origin, body, metadata.fetchedUtc, metadata.sha256)
    }
    @Query("SELECT url, fetchedUtc FROM sourceSnapshots WHERE origin = 'bundled'") fun snapshotHeaders(): List<SnapshotHeader>
    /** Every stored response, so a corrected parser can re-derive matches without any network access. */
    @Query("SELECT DISTINCT url FROM sourceSnapshots") fun snapshotUrls(): List<String>
    @Upsert suspend fun putRounds(rows: List<FootballRoundRow>)
    @Query("DELETE FROM footballRounds") suspend fun clearRounds()
    @Query("SELECT * FROM footballRounds WHERE season = :season") fun rounds(season: Int): List<FootballRoundRow>
    @Query("SELECT COUNT(*) FROM localAssets") suspend fun assetCount(): Int
    @Query("SELECT COUNT(*) FROM sourceSnapshots WHERE origin = :origin") suspend fun snapshotCount(origin: String): Int
}
