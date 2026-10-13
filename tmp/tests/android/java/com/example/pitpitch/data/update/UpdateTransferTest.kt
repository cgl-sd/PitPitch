package com.example.pitpitch.data.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class UpdateTransferTest {
    @Test fun pendingRecordsWithoutLocalUriRecoverAndCancelOnlyTheirOwnTransfer() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val transfer = UpdateTransfer(app)
        val version = UpdateVersion(0, 0)
        val name = "PitPitch-$version.apk"
        val base = "https://github.com/$UPDATE_REPOSITORY/releases/download/v$version/"
        val release = UpdateRelease(version, "v$version", ReleaseAsset(name, base + name, 1),
            ReleaseAsset("$name.sha256", "$base$name.sha256", 64))
        fun pending() = PendingUpdate(release, "0".repeat(64), "PitPitch-update-${UUID.randomUUID()}.apk", 0)
        fun enqueueOffline(value: PendingUpdate): Long = manager.enqueue(DownloadManager.Request(Uri.parse(release.apk.url))
            .setTitle("PitPitch download record regression")
            .setDescription(value.fileName)
            .setDestinationUri(Uri.fromFile(transfer.file(value.fileName)))
            // No network type is allowed: exercise actual pending provider records without HTTP or external dependencies.
            .setAllowedNetworkTypes(0))
        val first = pending()
        val second = pending()
        val firstId = enqueueOffline(first)
        var secondId = 0L
        try {
            secondId = enqueueOffline(second)
            val saved = first.copy(downloadId = firstId)
            manager.query(DownloadManager.Query().setFilterById(firstId)).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(null, cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)))
            }
            assertTrue(transfer.inspect(saved) is TransferSnapshot.Progress)
            assertEquals(firstId, transfer.recoverId(first))
            assertEquals(TransferSnapshot.Missing, transfer.inspect(first.copy(downloadId = secondId)))
            transfer.remove(saved)
            assertEquals(TransferSnapshot.Missing, transfer.inspect(saved))
            assertTrue(transfer.inspect(second.copy(downloadId = secondId)) is TransferSnapshot.Progress)
        } finally {
            manager.remove(firstId)
            if (secondId != 0L) manager.remove(secondId)
            transfer.file(first.fileName).delete()
            transfer.file(second.fileName).delete()
        }
    }
}
