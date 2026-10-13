package com.example.pitpitch.data.update

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private val tag = "v0.7.1"
    private val apkName = "PitPitch-0.7.1.apk"
    private val apk = ReleaseAsset(apkName, "https://github.com/cgl-sd/PitPitch/releases/download/$tag/$apkName", 4000)
    private val checksumAsset = ReleaseAsset("$apkName.sha256", "${apk.url}.sha256", 90)
    private val metadata = ReleaseMetadata(tag, false, false, listOf(apk, checksumAsset))
    private val expected = UpdateVersion(7, 1)
    private val digest = "0123456789abcdef".repeat(4)

    @Test fun versionsAreStrictAndComparedNumerically() {
        assertEquals(expected, UpdateVersion.parse("0.7.1"))
        assertTrue(UpdateVersion.parse("0.10.0")!! > UpdateVersion.parse("0.9.99")!!)
        assertTrue(UpdateVersion.parse("0.7.10")!! > expected)
        assertEquals(0, expected.compareTo(UpdateVersion(7, 1)))
        for (bad in listOf("", "v0.7.1", "1.7.1", "0.7", "0.07.1", "0.7.01", "0.7.1-beta", "0.7.1+build", " 0.7.1", "0.2147483648.1", "0.-1.0")) {
            assertNull(bad, UpdateVersion.parse(bad))
        }
    }

    @Test fun malformedDraftAndIncompleteReleasesAreRejected() {
        assertEquals(expected, UpdatePolicy.release(metadata).version)
        assertEquals(expected, UpdatePolicy.release(metadata.copy(tag = "0.7.1", assets = metadata.assets.map { it.copy(url = it.url.replace("v0.7.1", "0.7.1")) })).version)
        for (bad in listOf(
            metadata.copy(draft = true), metadata.copy(prerelease = true), metadata.copy(tag = "v0.7.1-rc.1"),
            metadata.copy(assets = listOf(apk)), metadata.copy(assets = listOf(checksumAsset)),
            metadata.copy(assets = listOf(apk, apk, checksumAsset)),
            metadata.copy(assets = listOf(apk.copy(name = "other.apk"), checksumAsset)),
            metadata.copy(assets = listOf(apk.copy(size = 0), checksumAsset)),
            metadata.copy(assets = listOf(apk, checksumAsset.copy(size = 4097))),
        )) rejects { UpdatePolicy.release(bad) }
    }

    @Test fun foreignInsecureAndAmbiguousAssetUrlsAreRejected() {
        assertTrue(UpdatePolicy.validAssetUrl(apk.url, tag, apkName))
        for (bad in listOf(
            apk.url.replace("https:", "http:"), apk.url.replace("github.com", "github.com.evil.test"),
            apk.url.replace("cgl-sd", "someone-else"), apk.url.replace("PitPitch/releases", "Other/releases"),
            apk.url.replace("v0.7.1", "v0.7.2"), apk.url.replace("github.com/", "user@github.com/"),
            apk.url.replace("github.com/", "github.com:443/"), "${apk.url}?download=1", "${apk.url}#fragment",
            apk.url.replace("PitPitch-0.7.1.apk", "PitPitch%2D0.7.1.apk"),
        )) {
            assertFalse(bad, UpdatePolicy.validAssetUrl(bad, tag, apkName))
            rejects { UpdatePolicy.release(metadata.copy(assets = listOf(apk.copy(url = bad), checksumAsset))) }
        }
        rejects { UpdatePolicy.release(metadata.copy(assets = listOf(apk, checksumAsset.copy(url = "https://evil.test/digest")))) }
    }

    @Test fun checksumMustBeACompleteDigestForTheExactApk() {
        assertEquals(digest, UpdatePolicy.checksum("$digest  $apkName\n", apkName))
        assertEquals(digest, UpdatePolicy.checksum("${digest.uppercase()} *$apkName\n", apkName))
        assertEquals(digest, UpdatePolicy.checksum(digest, apkName))
        for (bad in listOf("", digest.dropLast(1), "g".repeat(64), "$digest  other.apk", "$digest  $apkName\n$digest  $apkName")) {
            rejects { UpdatePolicy.checksum(bad, apkName) }
        }
        assertTrue(UpdatePolicy.matchesDigest(digest, digest))
        assertFalse(UpdatePolicy.matchesDigest(digest, "f".repeat(64)))
        assertFalse(UpdatePolicy.matchesDigest("", ""))
        assertFalse(UpdatePolicy.matchesDigest(digest, digest.dropLast(1)))
    }

    @Test fun archivedApkMustMatchPackageReleaseAndCurrentSignersWithAnIncreasingCode() {
        fun validate(packageName: String = "com.example.pitpitch", version: String? = "0.7.1", code: Long = 18,
                     installedSigners: Set<String> = setOf("installed-cert"), archiveSigners: Set<String> = setOf("installed-cert")) {
            UpdatePolicy.validateArchive("com.example.pitpitch", 17, installedSigners, packageName, version, code, archiveSigners, expected)
        }
        validate()
        rejects { validate(packageName = "com.evil.pitpitch") }
        rejects { validate(version = "0.7.2") }
        rejects { validate(version = null) }
        rejects { validate(code = 17) }
        rejects { validate(code = 16) }
        rejects { validate(archiveSigners = setOf("different-cert")) }
        rejects { validate(archiveSigners = setOf("installed-cert", "extra-cert")) }
        rejects { validate(installedSigners = emptySet(), archiveSigners = emptySet()) }
    }

    @Test fun onlyVerifiedReadyStateAllowsInstallAndBusyTransfersCannotStartAnotherCheck() {
        for (phase in UpdatePhase.entries) {
            val state = UpdateState(phase, "0.7.0", UpdatePolicy.release(metadata))
            assertEquals(phase.name, phase == UpdatePhase.READY, state.canInstall)
            if (phase in listOf(UpdatePhase.RECOVERING, UpdatePhase.CHECKING, UpdatePhase.PREPARING, UpdatePhase.DOWNLOADING,
                UpdatePhase.VERIFYING, UpdatePhase.PERMISSION, UpdatePhase.INSTALLER, UpdatePhase.AVAILABLE, UpdatePhase.READY)) {
                assertFalse(phase.name, state.canCheck)
            }
        }
        assertTrue(UpdateState(UpdatePhase.ERROR).canCheck)
        assertFalse(UpdateState(UpdatePhase.PERMISSION).canCancelDownload)
        assertFalse(UpdateState(UpdatePhase.INSTALLER).canCancelDownload)
        assertFalse(UpdateState(UpdatePhase.READY).canCancelDownload)
    }

    private fun rejects(block: () -> Unit) {
        try { block() } catch (_: IllegalArgumentException) { return } catch (_: IllegalStateException) { return }
        fail("Expected malformed update to be rejected")
    }
}
