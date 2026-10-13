package com.example.pitpitch.data

import android.content.Context
import org.json.JSONObject
import kotlin.math.cos

data class TrackPoint(val x: Double, val y: Double)
data class CircuitMap(val name: String, val lengthMetres: Int?, val points: List<TrackPoint>)
/** Equirectangular local projection, preserving proportions and north-up orientation. */
fun projectedTrack(points: List<TrackPoint>): List<TrackPoint> {
    if (points.size < 2) return emptyList()
    val latitude = points.map { it.y }.average()
    val longitudeScale = cos(Math.toRadians(latitude))
    val projected = points.map { TrackPoint(it.x * longitudeScale, -it.y) }
    val left = projected.minOf { it.x }; val top = projected.minOf { it.y }
    val span = maxOf(projected.maxOf { it.x } - left, projected.maxOf { it.y } - top)
    if (span <= 0) return emptyList()
    return projected.map { TrackPoint((it.x - left)/span, (it.y - top)/span) }
}
class CircuitMaps(context: Context, private val db: com.example.pitpitch.data.db.PitPitchDatabase = com.example.pitpitch.data.db.PitPitchDatabase.build(context)) {
    private val ids = mapOf("albert_park" to "au-1953", "bahrain" to "bh-2002", "shanghai" to "cn-2004", "catalunya" to "es-1991",
        "monaco" to "mc-1929", "villeneuve" to "ca-1978", "ricard" to "fr-1969", "red_bull_ring" to "at-1969", "silverstone" to "gb-1948",
        "hockenheimring" to "de-1932", "hungaroring" to "hu-1986", "spa" to "be-1925", "monza" to "it-1922", "marina_bay" to "sg-2008",
        "sochi" to "ru-2014", "suzuka" to "jp-1962", "americas" to "us-2012", "rodriguez" to "mx-1962", "interlagos" to "br-1940",
        "yas_marina" to "ae-2009", "imola" to "it-1953", "nurburgring" to "de-1927", "portimao" to "pt-2008", "mugello" to "it-1914",
        "sepang" to "my-1999", "istanbul" to "tr-2005", "zandvoort" to "nl-1948", "jeddah" to "sa-2021", "miami" to "us-2022",
        "losail" to "qa-2004", "baku" to "az-2016", "vegas" to "us-2023", "madring" to "es-2026")
    private val maps by lazy {
        val body = db.localData().documentBody("circuit-maps/circuits.geojson") ?: "{\"features\":[]}"
        JSONObject(body).getJSONArray("features").objects().associate { feature ->
            val properties = feature.getJSONObject("properties")
            val coordinates = feature.getJSONObject("geometry").getJSONArray("coordinates")
            properties.getString("id") to CircuitMap(properties.getString("Name"), properties.optInt("length").takeIf { it > 0 },
                projectedTrack((0 until coordinates.length()).map { i -> val p = coordinates.getJSONArray(i); TrackPoint(p.getDouble(0), p.getDouble(1)) }))
        }
    }
    fun find(id: String?): CircuitMap? = ids[id]?.let { maps[it] }
}
