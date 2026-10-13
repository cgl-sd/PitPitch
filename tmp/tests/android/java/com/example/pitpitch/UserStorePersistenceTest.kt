package com.example.pitpitch

import androidx.datastore.preferences.core.*
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pitpitch.data.local.UserStore
import com.example.pitpitch.data.local.createUserPreferencesStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class UserStorePersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun isolated(block: suspend (File, CoroutineScope) -> Unit) {
        val dir = File(context.noBackupFilesDir, "PreferenceValidation-${UUID.randomUUID()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try { block(dir, scope) }
        finally { scope.coroutineContext[Job]!!.cancelAndJoin(); dir.deleteRecursively() }
    }
    @Test fun corruptFileIsPreservedBeforeFirstReadRecoveryAndCanPersistAgain() = runBlocking {
        isolated { dir, scope ->
            val file = File(dir, "corrupt.preferences_pb")
            val invalid = byteArrayOf(0x0a, 0x7f, 0x01)
            // Seed before creating the independent DataStore, never overwrite an active singleton.
            file.writeBytes(invalid)
            val diagnostics = File(dir, "diagnostics")
            val store = createUserPreferencesStore(file, diagnostics, scope)
            assertTrue(store.data.first().asMap().isEmpty())
            val copies = diagnostics.listFiles()!!.toList()
            assertEquals(1, copies.size)
            assertArrayEquals(invalid, copies.single().readBytes())
            store.edit { it[booleanPreferencesKey("utc")] = true; it[stringSetPreferencesKey("favorites")] = setOf("favorite") }
            assertTrue(store.data.first()[booleanPreferencesKey("utc")] == true)
            assertEquals(setOf("favorite"), store.data.first()[stringSetPreferencesKey("favorites")])
            assertEquals(1, diagnostics.listFiles()!!.size)
        }
    }
    @Test fun ordinaryReadIOExceptionIsNotCorruptionAndDoesNotBecomeSuccess() = runBlocking {
        isolated { dir, scope ->
            val parent = File(dir, "not-a-directory").apply { writeText("preserve this file") }
            val diagnostics = File(dir, "diagnostics")
            val store = createUserPreferencesStore(File(parent, "user.preferences_pb"), diagnostics, scope)
            try { store.data.first(); fail("Ordinary I/O was converted to empty success") }
            catch (_: IOException) { }
            assertEquals("preserve this file", parent.readText())
            assertFalse(diagnostics.exists())
        }
    }
    @Test fun genuinelyMissingPreferenceParentCanBeCreatedAndPersisted() = runBlocking {
        isolated { dir, scope ->
            val file = File(dir, "missing/nested/user.preferences_pb")
            val diagnostics = File(dir, "diagnostics")
            val store = createUserPreferencesStore(file, diagnostics, scope)
            assertTrue(store.data.first().asMap().isEmpty())
            store.edit { it[booleanPreferencesKey("utc")] = true }
            assertTrue(file.isFile)
            assertEquals(true, store.data.first()[booleanPreferencesKey("utc")])
            assertFalse(diagnostics.exists())
        }
    }
    @Test fun restoredPreferenceSnapshotRetainsUserChoices() = runBlocking {
        isolated { dir, scope ->
            val diagnostics = File(dir, "diagnostics")
            val mainFile = File(dir, "original.preferences_pb")
            val main = createUserPreferencesStore(mainFile, diagnostics, scope)
            val original = UserStore(context, main)
            original.utc(true)
            original.toggle("favorite")
            original.homeCompetition("f1", false)
            original.homeTeam(com.example.pitpitch.data.FootballTeam(12345, "Saved team", null))
            val restoredFile = File(dir, "restored.preferences_pb")
            mainFile.copyTo(restoredFile)
            val restored = UserStore(context, createUserPreferencesStore(restoredFile, diagnostics, scope))
            val settings = restored.settings.first()
            assertTrue(settings.utc)
            assertEquals(setOf("favorite"), settings.favorites)
            assertTrue("f1" in settings.hiddenHomeCompetitions)
            assertEquals(12345, settings.homeTeam!!.id)
            assertEquals("Saved team", settings.homeTeam!!.name)
        }
    }
}
