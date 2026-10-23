package com.example.pitpitch.data.repository

import com.example.pitpitch.data.*
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant

interface RaceRepository {
    suspend fun season(year: Int, force: Boolean = false): List<F1Race>
    suspend fun snapshot(year: Int, force: Boolean = false): RemoteSnapshot<List<F1Race>> {
        val races = season(year, force)
        return RemoteSnapshot(races, Instant.now().toString(), cached = !force)
    }
}
class JolpicaRaceRepository(private val client: JsonClient) : RaceRepository {
    private fun url(year: Int) = "https://api.jolpi.ca/ergast/f1/$year.json?limit=100"
    private fun parse(body: String, year: Int) = F1Api.parseSeason(body, year)
    override suspend fun season(year: Int, force: Boolean): List<F1Race> = snapshot(year, force).data
    override suspend fun snapshot(year: Int, force: Boolean): RemoteSnapshot<List<F1Race>> {
        require(year in f1ArchiveYears())
        val address = url(year)
        if (!force) client.archived(address) { parse(it, year) }?.let { snapshot ->
            return snapshot.copy(data = snapshot.data.sortedBy { it.round })
        }
        return client.load(address) { parse(it, year) }.let { snapshot ->
            snapshot.copy(data = snapshot.data.sortedBy { it.round })
        }
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
