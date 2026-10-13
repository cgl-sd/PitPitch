package com.example.pitpitch.data.repository

import com.example.pitpitch.data.*
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDate

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
