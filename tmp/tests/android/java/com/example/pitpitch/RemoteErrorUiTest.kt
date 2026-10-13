package com.example.pitpitch

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.app.*
import com.example.pitpitch.data.*
import com.example.pitpitch.data.repository.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.SocketTimeoutException

@RunWith(AndroidJUnit4::class)
class RemoteErrorUiTest {
    @get:Rule val rule = createComposeRule()
    @Test fun timeoutRateLimitServerFailureThenRetryResolveThroughViewModelAndPage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.example.pitpitch.data.db.PitPitchDatabase::class.java).build()
        var failure: Exception? = SocketTimeoutException()
        val body = InstrumentationRegistry.getInstrumentation().context.assets.open("espn-match.json").bufferedReader().use { it.readText() }
        val client = JsonClient(transport = { url -> failure?.let { throw it }; if ("/standings?" in url) "{\"season\":{\"year\":2026},\"children\":[]}" else body })
        val matchTag = "match-fb-espn-eng.1-" + org.json.JSONObject(body).getJSONArray("events").getJSONObject(0).getString("id")
        val owner = ViewModelStore()
        lateinit var model: SportsViewModel
        try {
            rule.runOnUiThread {
                model = SportsViewModel(context.applicationContext as Application, client, object: RaceRepository { override suspend fun season(year: Int, force: Boolean) = emptyList<F1Race>() }, db = db)
                owner.put("test", model)
            }
            rule.setContent { SportsApp(model) }
            rule.waitUntil(10000) { rule.onAllNodesWithTag("nav-1").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("nav-1").performClick()
            for (error in listOf(SocketTimeoutException(), SportsHttpException(429), SportsHttpException(500))) {
                failure = error
                rule.runOnUiThread { model.refreshFootball() }
                rule.waitUntil(10000) { !model.state.value.footballLoading && model.state.value.footballError?.contains(readableError(error)) == true }
                rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
                rule.onNodeWithText("更新失败").assertExists()
            }
            failure = null
            rule.onNodeWithText("重试").performClick()
            rule.waitUntil(10000) { rule.onAllNodesWithTag(matchTag).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("match-fb-demo-1").assertDoesNotExist()
        } finally {
            rule.runOnUiThread { owner.clear() }
            db.close()
        }
    }
}
