package com.example.pitpitch.data.repository

import com.example.pitpitch.data.*
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDate

/** The UI sees domain models only. No secret keys or fictitious remote fixtures. */
interface FootballRepository { suspend fun season(year: Int): RemoteSnapshot<List<FootballMatch>> }
class DemoFootballRepository : FootballRepository {
    override suspend fun season(year: Int) = RemoteSnapshot(matches(), java.time.Instant.now().toString())
    fun matches(): List<FootballMatch> {
        val today = LocalDate.now()
        val base = sampleMatches.mapIndexed { index, match ->
            match.copy(date = today.plusDays(if (index == 1) 1 else 0).toString(),
                kickoffUtc = "${today.plusDays(if (index == 1) 1 else 0)}T12:00:00Z",
                lineupAvailable = index != 1, eventsAvailable = index != 1, statsAvailable = index != 1)
        }
        return base + listOf(
            base[1].copy(id = "fb-demo-postponed", status = "演示延期", score = "—", lineupAvailable = false),
            base[0].copy(id = "fb-demo-cancelled", status = "演示取消", score = "—", date = today.minusDays(1).toString(), lineupAvailable = false, eventsAvailable = false, statsAvailable = false),
            base[0].copy(id = "fb-demo-partial", competition = "演示 · 西班牙联赛", home = "海湾队", away = "山城队", homeShort = "海湾", awayShort = "山城", statsAvailable = false, eventsAvailable = false),
        )
    }
}
/** Public provider adapter; missing lineup/statistics remain explicitly unavailable. */
class RemoteFootballRepository(private val client: JsonClient) : FootballRepository {
    override suspend fun season(year: Int): RemoteSnapshot<List<FootballMatch>> = client.load("https://api.openligadb.de/getmatchdata/bl1/$year", OpenLigaAdapter::matches)
}
interface RaceRepository { suspend fun season(year: Int, force: Boolean = false): List<F1Race> }
class JolpicaRaceRepository(private val archive: SportsArchive? = null) : RaceRepository {
    override suspend fun season(year: Int, force: Boolean): List<F1Race> {
        require(year in f1ArchiveYears())
        val archived = if (force) null else archive?.read("https://api.jolpi.ca/ergast/f1/$year.json?limit=100") { F1Api.parseSeason(it, year) }?.data
        return (archived ?: F1Api.loadSeason(year)).sortedBy { it.round }
    }
}
class SportsHttpException(val status: Int) : IOException("HTTP $status")
fun readableError(error: Throwable): String = when (error) {
    is SocketTimeoutException -> "请求超时，请稍后重试。"
    is UnknownHostException -> "无法连接网络，请检查连接后重试。"
    is SportsHttpException -> when (error.status) {
        429 -> "数据源请求过于频繁，请稍后重试。"
        in 500..599 -> "数据源暂时不可用，请稍后重试。"
        else -> "数据请求失败（${error.status}），请稍后重试。"
    }
    else -> "暂时无法读取数据，请检查网络后重试。"
}
