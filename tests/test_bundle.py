"""GET /bundle -- the offline downlink the mobile app carries to sea.

The single property worth testing here is the one docs/MOBILE_APP.md §4.2
names as the gate: /bundle is a FAN-OUT, not a second brain. For every
zone, the verdict it serves must be the verdict /ask serves. The moment
those can differ, ORCA has two safety answers and no defensible story
about which was right -- which is the same argument that keeps
orca/policy.py out of every client.

Every test here runs with no key, no network and no model.
"""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from data.fetch import ZONES
from orca.api import app

client = TestClient(app)

# The fields policy.py owns. A difference in ANY of these between the two
# endpoints is the failure this file exists to catch. `recommendation`
# (the prose) is deliberately NOT here: /ask may have an LLM re-word it,
# /bundle never does, and wording is not a safety property.
VERDICT_FIELDS = ("action", "severity", "chosen_zone", "primary_zone")


@pytest.fixture(scope="module")
def bundle() -> dict:
    response = client.get("/bundle")
    if response.status_code == 503:
        pytest.skip("no cached observations -- run `python -m data.fetch`")
    assert response.status_code == 200, response.text
    return response.json()


def test_the_bundle_covers_every_zone_orca_knows(bundle):
    """A crew that can only reach one harbour needs to know about the
    others -- an offline bundle missing zones cannot answer "where else?"."""
    assert bundle["zone_count"] == len(ZONES)
    assert {z["primary_zone"]["name"] for z in bundle["zones"]} == {z["name"] for z in ZONES}


@pytest.mark.parametrize("zone", ZONES, ids=lambda z: z["name"])
def test_bundle_verdict_is_identical_to_ask(bundle, zone):
    """THE CONTRACT. Same zone, same cache, same answer.

    If this fails, do not "fix" it by adjusting the expected value --
    /bundle has grown a second code path and that is the bug.
    """
    asked = client.post("/ask", json={
        "query": zone["name"], "lat": zone["lat"], "lon": zone["lon"],
    })
    assert asked.status_code == 200, asked.text
    from_ask = asked.json()
    from_bundle = next(
        z for z in bundle["zones"] if z["primary_zone"]["name"] == zone["name"]
    )
    for field in VERDICT_FIELDS:
        assert from_bundle[field] == from_ask[field], (
            f"{zone['name']}: /bundle and /ask disagree on {field!r}"
        )


def test_every_reading_still_carries_its_provenance(bundle):
    """CLAUDE.md rule 3 does not weaken because the reading travelled in a
    bundle. A number on a phone at sea is still a number shown to a user."""
    seen_any = False
    for entry in bundle["zones"]:
        for obs in entry["evidence"]:
            seen_any = True
            for field in ("source", "valid_time", "confidence", "provenance", "id"):
                assert obs.get(field) not in (None, ""), f"{field} missing from {obs}"
    assert seen_any, "bundle carried no evidence at all"


def test_fetched_at_is_exposed_so_the_client_can_age_the_bundle(bundle):
    """freshness_min is computed at FETCH time and does not grow while the
    bundle sits on a phone (docs/MOBILE_APP.md §4.3). The device can only
    compute the age that actually matters at sea -- now minus fetched_at --
    if the server hands it fetched_at. Both levels must carry it."""
    assert bundle["cache_fetched_at"]
    assert bundle["latest_reading_time"]
    for entry in bundle["zones"]:
        for obs in entry["evidence"]:
            assert obs["fetched_at"], "per-observation fetched_at is required for §4.3"


def test_a_zone_subset_is_honoured(bundle):
    """`bundle` is requested for its skip guard (see above)."""
    assert bundle
    response = client.get("/bundle", params={"zones": "Chennai,Mandapam"})
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["zone_count"] == 2
    assert {z["primary_zone"]["name"] for z in body["zones"]} == {"Chennai", "Mandapam"}


def test_an_unknown_zone_is_refused_loudly_not_silently_dropped(bundle):
    """Rule 1's shape applied to a query parameter: a bundle that quietly
    returns nine zones when ten were asked for lets a crew sail believing
    they carry an advisory for a harbour they do not.

    Takes `bundle` only for its skip guard -- with an empty cache there is
    nothing to assert about and the fixture skips the whole module.
    """
    assert bundle
    response = client.get("/bundle", params={"zones": "Chennai,Atlantis"})
    assert response.status_code == 400
    assert "atlantis" in response.text.lower()


def test_the_bundle_is_reproducible_from_the_same_cache(bundle):
    """Two downloads in harbour must not disagree. Everything but
    generated_at is derived from the observations themselves."""
    again = client.get("/bundle").json()
    assert again["cache_fetched_at"] == bundle["cache_fetched_at"]
    assert again["latest_reading_time"] == bundle["latest_reading_time"]
    assert [z["action"] for z in again["zones"]] == [z["action"] for z in bundle["zones"]]


# --- the maritime boundary, for the mobile boundary watch ---------------

def test_the_bundle_carries_the_real_maritime_boundary(bundle):
    """The mobile app's background boundary watch runs on this geometry.
    Without it the phone cannot warn anyone, and it must never fall back
    to a hardcoded line."""
    boundary = bundle["boundary"]
    assert boundary is not None, "no IMBL geometry -- run `python -m data.fetch`"
    assert boundary["segments"], "boundary carried no segments"
    assert "Marine Regions" in boundary["source"]
    assert boundary["provenance"].startswith("http")
    # Real coordinates, bracketing the India-Sri Lanka boundary from the
    # Palk Strait down past Kanyakumari. Bounds are INCLUSIVE: the
    # southern terminus sits at exactly 5.0 N, and an exclusive `5.0 <`
    # failed on the real published geometry. A round number is what a
    # negotiated maritime boundary looks like, not a sign of bad data.
    for segment in boundary["segments"]:
        for lat, lon in segment:
            assert 4.0 <= lat <= 15.0 and 75.0 <= lon <= 85.0, (lat, lon)


def test_the_boundary_bands_come_from_the_geofence_agent_not_a_second_copy(bundle):
    """THE reason the bands are shipped rather than hardcoded in Java: a
    second copy of a safety constant is a second thing that can disagree
    with orca/agents.py. If these ever diverge, the phone and the advisory
    warn at different distances about the same boundary."""
    from orca import agents
    bands = bundle["boundary"]["bands_km"]
    assert bands["urgent"] == agents.IMBL_URGENT_KM
    assert bands["warning"] == agents.IMBL_WARNING_KM
    assert bands["advisory"] == agents.IMBL_ADVISORY_KM
    # Ordering the client relies on when picking a band.
    assert bands["urgent"] < bands["warning"] < bands["advisory"]


# --- Potential Fishing Zones (SIH26176's first example query) -----------

def test_pfz_ranks_every_zone_and_cites_its_readings():
    """"Where is the nearest Potential Fishing Zone today?" is the first
    example query in the problem statement. Every number shown must still
    trace to a MarineObservation (CLAUDE.md rule 3)."""
    response = client.get("/pfz")
    if response.status_code == 503:
        pytest.skip("no cached observations")
    body = response.json()
    assert {z["zone"] for z in body["zones"]} == {z["name"] for z in ZONES}
    for entry in body["zones"]:
        for reading in (entry["chlorophyll"], entry["sst"]):
            if reading is not None:
                for field in ("value", "unit", "source", "valid_time", "confidence", "id"):
                    assert reading.get(field) not in (None, ""), entry


def test_pfz_uses_the_eo_agents_thresholds_not_a_second_copy():
    """A second productivity rule would be a second thing that can
    disagree with the agent whose finding the verdict already cites."""
    from orca import agents
    criteria = client.get("/pfz").json()["criteria"]
    assert criteria["chlorophyll_min_mg_m3"] == agents.CHLOROPHYLL_PRODUCTIVE_MG_M3
    assert criteria["sst_range_c"] == list(agents.SST_PRODUCTIVE_RANGE_C)


def test_a_cloudy_zone_is_unseen_not_unproductive():
    """THE distinction that matters here. VIIRS cannot see through cloud,
    and six of ten zones had no usable pixel in a 15-day window. "We could
    not see" and "there are no fish" are different statements, and
    collapsing them into False is the fabrication rule 1 forbids."""
    entries = client.get("/pfz").json()["zones"]
    for entry in entries:
        if entry["chlorophyll"] is None:
            assert entry["productive"] is None, entry["zone"]
            assert "cannot tell" in entry["why"].lower() or "no cloud-free" in entry["why"].lower()
        else:
            assert entry["productive"] in (True, False), entry["zone"]


def test_unseen_zones_sort_last_but_are_never_dropped():
    """A crew needs to know ORCA could not see a place, not to have it
    quietly vanish from the list."""
    entries = client.get("/pfz").json()["zones"]
    seen_flags = [e["productive"] is not None for e in entries]
    # every True before every False: unseen zones are at the end
    assert seen_flags == sorted(seen_flags, reverse=True)
    assert len(entries) == len(ZONES)


def test_pfz_never_claims_a_catch_quantity():
    """INCOIS's own PFZ advisories carry this limitation. Chlorophyll is a
    productivity proxy, not a fish count."""
    body = client.get("/pfz").json()
    assert "not a catch" in body["criteria"]["note"].lower()
