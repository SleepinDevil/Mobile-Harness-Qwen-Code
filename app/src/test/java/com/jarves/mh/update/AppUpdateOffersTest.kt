package com.jarves.mh.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bucket scheme: stable = upstream code x 10000 (110000, 120000, ...);
 * betas for a release live strictly between it and the next stable
 * (110001..119999). Every offer just needs to be numerically newer.
 */
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

    private fun offers(stable: String?, debug: String?, installedCode: Long) =
        appUpdateOffers(stable, debug, "online", installedCode)

    @Test
    fun stableBuildSeesNoStableOfferForItsOwnVersion() {
        val result = offers(manifest(110000, "1.0.10"), debug = null, installedCode = 110000)
        assert(result.isEmpty())
    }

    @Test
    fun stableBuildSeesNewerStableAsOffer() {
        val result = offers(manifest(120000, "1.0.11"), debug = null, installedCode = 110000)
        assertEquals(1, result.size)
        assertEquals(AppUpdateChannel.STABLE, result.single().channel)
    }

    @Test
    fun stableBuildCanJoinNewerBeta() {
        val result = offers(manifest(110000, "1.0.10"), manifest(110005, "1.0.10-dbg5"), installedCode = 110000)
        assertEquals(1, result.size)
        assertEquals(AppUpdateChannel.DEBUG, result.single().channel)
    }

    @Test
    fun betaBuildSeesOnlyNewerBetaWithinItsBucket() {
        val result = offers(stable = null, manifest(110006, "1.0.10-dbg6"), installedCode = 110005)
        assertEquals(1, result.size)
        assertEquals(AppUpdateChannel.DEBUG, result.single().channel)
    }

    @Test
    fun betaBuildIgnoresStaleOrEqualBeta() {
        assert(offers(stable = null, manifest(110005, "1.0.10-dbg5"), installedCode = 110005).isEmpty())
        assert(offers(stable = null, manifest(110003, "1.0.10-dbg3"), installedCode = 110005).isEmpty())
    }

    @Test
    fun betaToNextStableIsANumericUpgrade() {
        // The user's scenario: on beta 5, stable 1.0.11 (120000) and beta 6
        // (110006) appear side by side; both install in place, newest first.
        val result = offers(
            manifest(120000, "1.0.11"),
            manifest(110006, "1.0.10-dbg6"),
            installedCode = 110005,
        )
        assertEquals(2, result.size)
        assertEquals(AppUpdateChannel.STABLE, result[0].channel)
        assertEquals(120000L, result[0].versionCode)
        assertEquals(AppUpdateChannel.DEBUG, result[1].channel)
    }

    @Test
    fun stableBuildIgnoresBetaOfAPastBucket() {
        val result = offers(stable = null, manifest(110003, "1.0.10-dbg3"), installedCode = 120000)
        assert(result.isEmpty())
    }

    @Test
    fun manifestWithoutVariantArtifactYieldsNoOffer() {
        val onlyOffline = manifest(120000, "1.0.11", variant = "offline")
        val result = offers(onlyOffline, debug = null, installedCode = 110000)
        assert(result.isEmpty())
    }

    @Test
    fun malformedManifestYieldsNoOffer() {
        val result = offers("{not json", debug = null, installedCode = 110000)
        assert(result.isEmpty())
    }
}
