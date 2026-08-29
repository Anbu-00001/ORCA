package org.orca.advisory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.time.Instant

/**
 * The boat-to-boat relay's accept/reject rules.
 *
 * These matter more than they look. A relayed advisory arrives from an
 * UNTRUSTED phone belonging to a stranger, and the decision to take it or
 * refuse it is the only thing standing between the fleet and an old or
 * malformed safety verdict propagating through it. Two phones and an
 * ocean are not available to test that, but the rules themselves are pure
 * functions and are tested here.
 *
 * Everything under test is deliberately free of Android APIs except
 * `android.util.Log`, which is no-opped by `unitTests.returnDefaultValues`.
 */
class FleetRelayTest {

    // The rules under test are companion-object functions: no radio, no
    // Context, no Android API beyond Log. That is structural, not a claim.

    private fun manifest(age: Int, zones: Int = 10, hops: Int = 0) =
        FleetRelay.Manifest(ageMinutes = age, zoneCount = zones, hops = hops)

    // --- what is worth taking -------------------------------------------

    @Test
    fun `anything beats having nothing`() {
        assertTrue(FleetRelay.isBetter(manifest(age = 2000), ourAgeMinutes = null))
    }

    @Test
    fun `a newer advisory is taken`() {
        assertTrue(FleetRelay.isBetter(manifest(age = 60), ourAgeMinutes = 600))
    }

    @Test
    fun `an older advisory is refused`() {
        assertFalse(FleetRelay.isBetter(manifest(age = 600), ourAgeMinutes = 60))
    }

    @Test
    fun `a tie is refused, because the transfer would buy nothing`() {
        // 52 KB over BLE costs battery that has to last the whole trip.
        assertFalse(FleetRelay.isBetter(manifest(age = 300), ourAgeMinutes = 300))
    }

    @Test
    fun `an empty advisory is never taken`() {
        // Zero zones is not an advisory, however fresh it claims to be.
        assertFalse(FleetRelay.isBetter(manifest(age = 0, zones = 0), ourAgeMinutes = 9999))
    }

    @Test
    fun `hop count never rejects a newer advisory`() {
        // A five-hop bundle that is newer than yours is still better than
        // yours. Hops are shown to the user, never used to filter.
        assertTrue(FleetRelay.isBetter(manifest(age = 30, hops = 5), ourAgeMinutes = 400))
    }

    // --- the manifest fits in an advertisement ---------------------------

    @Test
    fun `a manifest round-trips through the eight bytes BLE gives us`() {
        val original = manifest(age = 1234, zones = 10, hops = 3)
        val decoded = FleetRelay.decodeManifest(FleetRelay.encodeManifest(original))
        assertNotNull(decoded)
        assertEquals(1234, decoded!!.ageMinutes)
        assertEquals(10, decoded.zoneCount)
        assertEquals(3, decoded.hops)
    }

    @Test
    fun `a manifest survives the largest age the two age bytes can hold`() {
        // 65535 minutes is ~45 days. Anything older is pinned, not wrapped
        // -- an age that wrapped to a small number would make an ancient
        // advisory look fresh, which is the worst possible bug here.
        val decoded = FleetRelay.decodeManifest(FleetRelay.encodeManifest(manifest(age = 65535)))
        assertEquals(65535, decoded!!.ageMinutes)
    }

    @Test
    fun `garbage on the air is refused, not guessed at`() {
        assertNull(FleetRelay.decodeManifest(null))
        assertNull(FleetRelay.decodeManifest(ByteArray(3)))               // too short
        assertNull(FleetRelay.decodeManifest(byteArrayOf(9, 0, 0, 0, 0, 0, 0, 0)))  // wrong version
    }

    // --- accepting a bundle from a stranger ------------------------------

    private fun bundle(collectedAt: String, zones: Int = 2): String {
        val zoneArray = (1..zones).joinToString(",") {
            """{"action":"GO","primary_zone":{"name":"Zone$it","lat":10.0,"lon":80.0}}"""
        }
        return """{"cache_fetched_at":"$collectedAt","zones":[$zoneArray]}"""
    }

    @Test
    fun `a newer bundle is accepted and stamped with its hop count`() {
        val ours = Instant.parse("2026-08-29T06:00:00Z")
        val theirs = bundle("2026-08-29T12:00:00Z")
        val accepted = FleetRelay.validateReceived(theirs, ours)
        assertNotNull(accepted)
        val root = JSONObject(accepted!!)
        assertTrue(root.getBoolean("relayed"))
        assertEquals(1, root.getInt("hops"))
        assertNotNull(root.getString("relayed_at"))
    }

    @Test
    fun `hops increment on each boat, so the chain is visible`() {
        val ours = Instant.parse("2026-08-29T06:00:00Z")
        var json = FleetRelay.validateReceived(bundle("2026-08-29T12:00:00Z"), ours)!!
        // Pass it on twice more, each time to a boat holding something older.
        repeat(2) { json = FleetRelay.validateReceived(json, ours)!! }
        assertEquals(3, JSONObject(json).getInt("hops"))
    }

    @Test
    fun `a bundle no newer than ours is ignored`() {
        val ours = Instant.parse("2026-08-29T12:00:00Z")
        assertNull(FleetRelay.validateReceived(bundle("2026-08-29T12:00:00Z"), ours))
        assertNull(FleetRelay.validateReceived(bundle("2026-08-29T06:00:00Z"), ours))
    }

    @Test
    fun `a bundle with no collection time is refused`() {
        // Without it the advisory cannot be aged, and an advisory of
        // unknowable age must never be shown as if it were current.
        assertNull(FleetRelay.validateReceived("""{"zones":[{"action":"GO"}]}""", null))
    }

    @Test
    fun `a bundle with no zones is refused however fresh it claims to be`() {
        assertNull(FleetRelay.validateReceived("""{"cache_fetched_at":"2099-01-01T00:00:00Z","zones":[]}""", null))
    }

    @Test
    fun `unparseable bytes are refused rather than crashing the relay`() {
        assertNull(FleetRelay.validateReceived("not json at all", null))
        assertNull(FleetRelay.validateReceived("", null))
        assertNull(FleetRelay.validateReceived("""{"cache_fetched_at":"not-a-time","zones":[{}]}""", null))
    }

    @Test
    fun `a phone with nothing accepts any well-formed bundle`() {
        val accepted = FleetRelay.validateReceived(bundle("2026-08-29T12:00:00Z"), ourCollectedAt = null)
        assertNotNull(accepted)
    }

    @Test
    fun `the relay never rewrites a verdict it carries`() {
        // The whole safety argument: this class moves bytes, it does not
        // reason. A relayed GO must still be exactly the GO the shore
        // system issued.
        val original = bundle("2026-08-29T12:00:00Z")
        val accepted = FleetRelay.validateReceived(original, null)!!
        val before = JSONObject(original).getJSONArray("zones")
        val after = JSONObject(accepted).getJSONArray("zones")
        assertEquals(before.length(), after.length())
        for (i in 0 until before.length()) {
            assertEquals(
                before.getJSONObject(i).getString("action"),
                after.getJSONObject(i).getString("action"),
            )
        }
    }
}
