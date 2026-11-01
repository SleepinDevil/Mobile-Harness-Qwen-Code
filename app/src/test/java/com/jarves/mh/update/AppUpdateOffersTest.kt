package com.jarves.mh.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AppUpdateOffersTest {
    private fun manifest(versionCode: Long, versionName: String, variant: String = "online"): String =
        JSONObject()
            .put("versionCode", versionCode)
            .put("versionName", versionName)
            .put("notes", "notes")
            .put(
                "artifacts",
                JSONObject().put(
                    variant,
                    JSONObject()
                        .put("url", "https://example.invalid/app.apk")
                        .put("sha256", "abc")
                        .put("sizeBytes", 123),
                ),
            )
            .toString()

    private fun offers(stable: String?, debug: String?, installedCode: Long, installedBase: Long) =
        appUpdateOffers(stable, debug, "online", installedCode, installedBase)

    @Test
    fun stableBuildSeesNoStableOfferForItsOwnVersion() {
        val result = offers(manifest(11, "1.0.10"), debug = null, installedCode = 11, installedBase = 11)
        assert(result.isEmpty())
    }

    @Test
    fun stableBuildSeesNewerStableAsInstallable() {
        val result = offers(manifest(12, "1.0.11"), debug = null, installedCode = 11, installedBase = 11)
        assertEquals(1, result.size)
        assertEquals(AppUpdateChannel.STABLE, result.single().channel)
        assert(!result.single().requiresUninstall)
    }

    @Test
    fun stableBuildCanInstallDebugBetaDirectly() {
        val result = offers(manifest(11, "1.0.10"), manifest(90000001, "1.0.10-dbg1"), installedCode = 11, installedBase = 11)
        assertEquals(1, result.size)
        assertEquals(AppUpdateChannel.DEBUG, result.single().channel)
        assert(!result.single().requiresUninstall)
    }

    @Test
    fun debugBuildIgnoresStableOfItsOwnBase() {
        val result = offers(manifest(11, "1.0.10"), debug = null, installedCode = 90000001, installedBase = 11)
        assert(result.isEmpty())
    }

    @Test
    fun debugBuildSeesNewerBetaAndNewerStableWithUninstallWarning() {
        // Upstream moved to 1.0.11 (code 12) while the debug series advanced to dbg6.
        val result = offers(
            manifest(12, "1.0.11"),
            manifest(90000006, "1.0.10-dbg6"),
            installedCode = 90000005,
            installedBase = 11,
        )
        assertEquals(2, result.size)
        // Installable debug update first; stable switch (a downgrade Android
        // refuses in place) last and flagged for uninstall.
        assertEquals(AppUpdateChannel.DEBUG, result[0].channel)
        assert(!result[0].requiresUninstall)
        assertEquals(AppUpdateChannel.STABLE, result[1].channel)
        assert(result[1].requiresUninstall)
    }

    @Test
    fun debugBuildSeesNoStaleBetaOffer() {
        val result = offers(stable = null, manifest(90000001, "1.0.10-dbg1"), installedCode = 90000001, installedBase = 11)
        assert(result.isEmpty())
    }

    @Test
    fun manifestWithoutVariantArtifactYieldsNoOffer() {
        val onlyOffline = manifest(12, "1.0.11", variant = "offline")
        val result = offers(onlyOffline, debug = null, installedCode = 11, installedBase = 11)
        assert(result.isEmpty())
    }

    @Test
    fun malformedManifestYieldsNoOffer() {
        val result = offers("{not json", debug = null, installedCode = 11, installedBase = 11)
        assert(result.isEmpty())
    }
}
