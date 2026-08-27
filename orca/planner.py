"""query -> agents over cached evidence -> policy.resolve() -> structured answer.

This is where the §8.4 demo behaviour actually lives. policy.resolve() is
scoped to one zone; this module compares zones: if the primary (named or
nearest) zone doesn't cleanly resolve to GO, it looks at the other zones
in order and, if one resolves to a genuine GO (real opportunity evidence
behind it, not just an absence of data), recommends that one instead as
"SAFER ALTERNATIVE". If nothing nearby is clean, it reports the primary
zone's own decision honestly — no zone gets invented to paper over a
DO NOT GO.

Does not modify orca/schema.py or orca/policy.py.
"""
from __future__ import annotations

import hashlib
import json
import uuid
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

from data.fetch import ZONES
from orca.agents import (
    _haversine_km,
    eo_satellite_agent,
    geofence_agent,
    hazard_agent,
    ocean_state_agent,
    weather_agent,
)
from orca.policy import RISK_OVERRIDE_THRESHOLD, Decision, Finding, resolve
from orca.schema import MarineObservation

CACHE_DIR = Path(__file__).resolve().parent.parent / "data" / "cache"
# Tomorrow's forecast, for orca/agentic.py's data_lookup answers only --
# see data/fetch.py's fetch_tomorrow() docstring for why this is a wholly
# separate subdirectory, never mixed into CACHE_DIR: the live GO/DO NOT
# GO path (this file's build_recommendation(), every agent) must keep
# reading exactly what it always has. Path.glob("*.json") on CACHE_DIR is
# non-recursive, so files in here are already invisible to
# load_cached_observations() without any extra guard -- this constant
# just names the directory both sides agree on.
FORECAST_CACHE_DIR = CACHE_DIR / "forecast"
ZONE_MATCH_TOLERANCE_DEG = 0.05

# R-60. The alternative search offers a zone the crew is expected to
# actually divert to, so it is bounded by how far that is. Unbounded, it
# sent Thoothukudi to Chennai (569 km) and Point Calimere to Chennai
# (320 km) with Karaikal 72 km away -- R-16 takes the first genuine GO in
# ZONES order, and Chennai is first in that list.
#
# Unlike the 2.5 m hard deny (R-7), this number has no Douglas-scale
# provenance and is not presented as though it did. It is one boat's
# divert range stated as arithmetic: a mechanised Tamil Nadu trawler
# cruising ~7 kn (~13 km/h) spends ~7.5 h steaming 100 km, which is the
# outer edge of what can be added to a single-day trip before fuel and
# crew endurance -- not weather -- become the binding constraint. A
# diversion the crew cannot reach before dark is not a safer alternative.
MAX_ALTERNATIVE_KM = 100.0

AGENTS = [eo_satellite_agent, ocean_state_agent, weather_agent, hazard_agent, geofence_agent]


def _load_observations_from(cache_dir: Path) -> list[MarineObservation]:
    observations: list[MarineObservation] = []
    for path in sorted(cache_dir.glob("*.json")):
        raw = json.loads(path.read_text())
        for item in raw:
            observations.append(
                MarineObservation(
                    variable=item["variable"],
                    value=item["value"],
                    unit=item["unit"],
                    lat=item["lat"],
                    lon=item["lon"],
                    valid_time=datetime.fromisoformat(item["valid_time"]),
                    fetched_at=datetime.fromisoformat(item["fetched_at"]),
                    source=item["source"],
                    confidence=item["confidence"],
                    freshness_min=item["freshness_min"],
                    provenance=item["provenance"],
                )
            )
    return observations


def load_cached_observations(cache_dir: Path | None = None) -> list[MarineObservation]:
    return _load_observations_from(Path(cache_dir) if cache_dir else CACHE_DIR)


def load_forecast_observations(cache_dir: Path | None = None) -> list[MarineObservation]:
    """Tomorrow's forecast only -- orca/agentic.py's data_lookup path is
    the only caller. Returns [] if the forecast cache hasn't been
    populated yet (e.g. an older data/fetch.py run, or this run's
    tomorrow-fetch failed) -- absent, not fabricated, per CLAUDE.md rule 1.
    """
    cache_dir = Path(cache_dir) if cache_dir else FORECAST_CACHE_DIR
    if not cache_dir.exists():
        return []
    return _load_observations_from(cache_dir)


def observation_id(obs: MarineObservation) -> str:
    key = f"{obs.source}|{obs.variable}|{obs.lat}|{obs.lon}|{obs.valid_time.isoformat()}"
    return "obs_" + hashlib.sha1(key.encode()).hexdigest()[:10]


def observations_for_zone(
    observations: list[MarineObservation], zone: dict, tolerance: float = ZONE_MATCH_TOLERANCE_DEG
) -> list[MarineObservation]:
    return [
        o for o in observations
        if abs(o.lat - zone["lat"]) < tolerance and abs(o.lon - zone["lon"]) < tolerance
    ]


def _zone_by_substring(query: str, zones: list[dict]) -> dict | None:
    """The zero-risk, zero-network first-pass zone match: does a known
    zone's name literally appear in the query? Extracted out of
    resolve_zone_from_query() so orca/agentic.py can run this exact check
    itself before ever considering an LLM guess -- a deterministic hit
    here always wins (see that module's answer_question())."""
    query_lower = query.lower()
    for zone in zones:
        if zone["name"].lower() in query_lower:
            return zone
    return None


def resolve_zone_from_query(query: str, lat: float, lon: float, zones: list[dict] | None = None) -> dict:
    zones = zones or ZONES
    return _zone_by_substring(query, zones) or min(
        zones, key=lambda z: (z["lat"] - lat) ** 2 + (z["lon"] - lon) ** 2
    )


def run_agents(observations: list[MarineObservation]) -> list[Finding]:
    return [agent(observations) for agent in AGENTS]


def _zone_verdict(decision: Decision, findings: list[Finding]) -> Decision:
    """Two fail-open corrections applied to one zone's Decision, both
    living here rather than in orca/policy.py, which stays frozen (N-5)
    with its three rules and its resolve([]) guard exactly as they are.

    Applied once, where zone_results is built, so the primary decision,
    the alternative search and zone_summaries all agree -- R-59 and R-39
    modify the same lines of that loop, and done separately the second
    would rewrite the first.

    The two conditions are disjoint: a zone with no observations has no
    danger to find. Order between them is for clarity, not correctness.
    """
    # R-39: no evidence at all. Five neutral findings are not five clean
    # bills of health, and "No hazards found; conditions acceptable" from
    # zero observations is the confident-gap failure of PRD §1.3.
    # Deliberately NOT "DO NOT GO" -- conflating "I know it is dangerous"
    # with "I do not know" teaches users to discount the one verdict that
    # must never be discounted (Open Decision 8, resolved).
    if not any(f.observations for f in findings):
        blind = ", ".join(f.agent_name for f in findings)
        return Decision(
            action="CANNOT ASSESS",
            reason="No observations available for this zone",
            chosen=None,
            overridden=[],
            explanation=f"No agent had evidence at this zone ({blind}); ORCA is refusing to decide.",
        )

    # R-59: danger that never reached rule 2. policy.py gates rule 2 on
    # opportunity AND danger, so hazards with nothing suggesting go fall
    # through to rule 3 and return GO. The trigger is inverted --
    # suggests_go goes false when water is cold or chlorophyll is
    # cloud-masked -- so the worse the fishing looks, the more likely the
    # safety override is skipped entirely.
    if decision.action == "GO":
        danger = [f for f in findings if f.risk_level >= RISK_OVERRIDE_THRESHOLD]
        if danger:
            # max(), not danger[0]: R-37 stays open in the frozen
            # policy.py by decision, but there is no reason to introduce
            # a second list-order pick on a path written today.
            worst = max(danger, key=lambda f: f.risk_level)
            return Decision(
                action="SAFER ALTERNATIVE",
                reason=worst.reason,
                chosen=None,
                overridden=[],  # nothing was sacrificed -- R-11
                explanation=f"Hazard with no competing opportunity: {worst.reason}",
            )

    return decision


def _collect_evidence(findings: list[Finding]) -> list[MarineObservation]:
    seen: dict[str, MarineObservation] = {}
    for finding in findings:
        for obs in finding.observations:
            seen[observation_id(obs)] = obs
    return list(seen.values())


def _render_text(final_action: str, primary_zone: dict, final_zone: dict, primary_decision: Decision) -> str:
    if final_action == "GO":
        return f"Go to {final_zone['name']}. {primary_decision.reason}."
    # R-39: state the absence of evidence as an absence. Never "do not go
    # to X", which would report ignorance as danger.
    if final_action == "CANNOT ASSESS":
        if final_zone["name"] != primary_zone["name"]:
            return (
                f"ORCA cannot assess {primary_zone['name']} — no readings are available for it. "
                f"Go to {final_zone['name']} instead."
            )
        return (
            f"ORCA cannot assess {primary_zone['name']} — no readings are available for it, "
            "and no assessable zone was found nearby. This is not a judgement that conditions "
            "are safe; do not treat it as one."
        )
    if final_action == "SAFER ALTERNATIVE" and final_zone["name"] != primary_zone["name"]:
        return (
            f"Do not go to {primary_zone['name']} — {primary_decision.reason}. "
            f"Go to {final_zone['name']} instead."
        )
    if final_action == "SAFER ALTERNATIVE":
        return (
            f"Conditions at {primary_zone['name']} are borderline — {primary_decision.reason}. "
            "No clearly safer nearby zone found; proceed with caution or wait."
        )
    return f"Do not go to {primary_zone['name']} — {primary_decision.reason}. No safer nearby zone found."


@dataclass
class Recommendation:
    id: str
    action: str
    reason: str
    recommendation: str
    chosen_zone: dict | None
    overridden: list[Finding] = field(default_factory=list)
    evidence: list[MarineObservation] = field(default_factory=list)
    offline_mode: bool = False
    # Full reasoning trace, additive to the API_CONTRACT.md shape above --
    # existing consumers ignore unknown keys. Both fields surface
    # computation build_recommendation() already does internally; nothing
    # here is fabricated for the sake of a nicer chart (CLAUDE.md rule 1).
    agent_findings: list[Finding] = field(default_factory=list)  # primary zone's 5 raw agent findings
    zone_summaries: list[dict] = field(default_factory=list)  # one entry per evaluated zone
    # orca/agentic.py's fields, additive same as above. Defaults reproduce
    # today's plain build_recommendation() output exactly -- a caller that
    # never touches orca.agentic sees no difference at all.
    agentic_used: bool = False
    detected_language: str = "en"
    cited_evidence_ids: list[str] = field(default_factory=list)
    # How the answered zone was arrived at, so the UI (and the composed
    # wording) can be honest about it rather than presenting a
    # nearest-by-distance guess as if it were what was asked:
    #   "exact"      -- the query literally named this zone
    #   "inferred"   -- an LLM mapped a landmark/description onto it
    #   "remembered" -- carried over from the conversation's prior turn
    #   "fallback"   -- nothing matched; nearest zone by coordinates
    zone_match: str = "exact"
    answer_kind: str = "verdict"  # "verdict" | "data_lookup" | "off_topic"
    time_frame: str = "now"  # "now" | "tomorrow"
    coverage_note: str | None = None  # honest caveat when zone_match == "fallback"
    # The true cross-zone ordering, present only when the question was a
    # comparison ("which place has the worst waves?"). Computed in plain
    # Python from real observations by orca/agentic.py's _rank_zones();
    # None means no comparison was asked for, and the composer is told in
    # that case that it may not rank places at all.
    ranking: list[dict] | None = None
    lookup: dict | None = None  # the one real observation a data_lookup asked for
    # The zone the QUESTION was about, which is not the same thing as
    # chosen_zone (where we're sending them). On a SAFER ALTERNATIVE they
    # differ, and on a DO NOT GO chosen_zone is None entirely. Anything
    # resolving a follow-up pronoun ("what's the wave height *there*?")
    # wants this one -- see web/index.html's rememberTurn().
    primary_zone: dict | None = None
    # R-38 / R-40. Both are read off findings build_recommendation()
    # already has; neither is a separate calculation and neither
    # overloads `action`.
    severity: str = "none"  # "none" | "advisory" | "hard_deny" | "unknown"
    blind_agents: list[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "action": self.action,
            "reason": self.reason,
            "recommendation": self.recommendation,
            "chosen_zone": self.chosen_zone,
            "overridden": [{"agent": f.agent_name, "reason": f.reason} for f in self.overridden],
            "evidence": [{**o.to_dict(), "id": observation_id(o)} for o in self.evidence],
            "offline_mode": self.offline_mode,
            "agent_findings": [
                {
                    "agent": f.agent_name,
                    "suggests_go": f.suggests_go,
                    "risk_level": f.risk_level,
                    "hard_deny": f.hard_deny,
                    "reason": f.reason,
                    "observation_ids": [observation_id(o) for o in f.observations],
                }
                for f in self.agent_findings
            ],
            "zone_summaries": self.zone_summaries,
            "agentic_used": self.agentic_used,
            "detected_language": self.detected_language,
            "cited_evidence_ids": self.cited_evidence_ids,
            "zone_match": self.zone_match,
            "answer_kind": self.answer_kind,
            "time_frame": self.time_frame,
            "coverage_note": self.coverage_note,
            "ranking": self.ranking,
            "lookup": self.lookup,
            "primary_zone": self.primary_zone,
            "severity": self.severity,
            "blind_agents": self.blind_agents,
        }


def build_recommendation(
    query: str,
    lat: float,
    lon: float,
    observations: list[MarineObservation] | None = None,
    offline_mode: bool = False,
    zones: list[dict] | None = None,
    resolved_zone: dict | None = None,
) -> Recommendation:
    zones = zones or ZONES
    observations = observations if observations is not None else load_cached_observations()
    if not observations:
        raise ValueError("No observations available to build a recommendation from")

    # resolved_zone lets orca/agentic.py hand in a zone it already picked
    # (via substring match or an LLM-constrained-to-real-zones guess)
    # instead of recomputing it here -- default None reproduces the plain
    # resolve_zone_from_query() behaviour exactly.
    primary_zone = resolved_zone or resolve_zone_from_query(query, lat, lon, zones)
    ordered_zones = [primary_zone] + [z for z in zones if z["name"] != primary_zone["name"]]

    zone_results: dict[str, tuple[dict, Decision, list[Finding]]] = {}
    for zone in ordered_zones:
        findings = run_agents(observations_for_zone(observations, zone))
        zone_results[zone["name"]] = (zone, _zone_verdict(resolve(findings), findings), findings)

    p_zone, p_decision, p_findings = zone_results[primary_zone["name"]]

    if p_decision.action == "GO":
        final_zone, final_action = p_zone, "GO"
        overridden: list[Finding] = []
        evidence_findings = p_findings
    else:
        alternative = None
        for zone in ordered_zones[1:]:
            z, d, f = zone_results[zone["name"]]
            if d.action == "GO" and d.chosen is not None:
                # R-60: only somewhere they could actually divert to.
                if _haversine_km(p_zone["lat"], p_zone["lon"], z["lat"], z["lon"]) > MAX_ALTERNATIVE_KM:
                    continue
                alternative = (z, d, f)
                break

        if alternative is not None:
            final_zone, _, alt_findings = alternative
            # R-39b: a CANNOT ASSESS primary keeps its own verdict and is
            # offered the alternative alongside it -- ORCA still cannot
            # assess where they asked. Relabelling it SAFER ALTERNATIVE
            # would render as "Do not go to X", conflating ignorance with
            # danger, which is exactly what R-39 forbids.
            final_action = "CANNOT ASSESS" if p_decision.action == "CANNOT ASSESS" else "SAFER ALTERNATIVE"
            overridden = [f for f in p_findings if f.suggests_go]
            evidence_findings = p_findings + alt_findings
        else:
            final_zone, final_action = p_zone, p_decision.action
            overridden = p_decision.overridden
            evidence_findings = p_findings

    # A concrete zone is only shown when we're actually sending them
    # somewhere: the primary zone itself (GO) or a real alternative
    # (SAFER ALTERNATIVE with a zone swap). A SAFER ALTERNATIVE with no
    # clean zone found, or a DO NOT GO, has nothing to point at.
    zone_was_swapped = final_zone["name"] != p_zone["name"]
    has_concrete_zone = final_action == "GO" or (
        final_action in ("SAFER ALTERNATIVE", "CANNOT ASSESS") and zone_was_swapped
    )
    chosen_zone = (
        {"name": final_zone["name"], "lat": final_zone["lat"], "lon": final_zone["lon"]}
        if has_concrete_zone
        else None
    )

    # Per-zone risk summary for the geospatial 3D view -- "worst" agent's
    # risk_level at that zone (max, not average: a single hard hazard
    # shouldn't get diluted by four calm agents). Every zone in `zones` is
    # evaluated above regardless of which one gets chosen, so this is a
    # real cross-zone picture, not just the primary/final pair.
    zone_summaries = [
        {
            "name": zone["name"],
            "lat": zone["lat"],
            "lon": zone["lon"],
            "action": decision.action,
            "risk_level": max((f.risk_level for f in findings), default=0.0),
            "hard_deny": any(f.hard_deny for f in findings),
        }
        for zone_name, (zone, decision, findings) in zone_results.items()
    ]

    # R-38: how strong the verdict is, without parsing prose. A hard deny
    # that got rerouted still reads as action "SAFER ALTERNATIVE", which
    # is indistinguishable from a mild wind override to the control-room
    # user (§2, P1) consuming this programmatically.
    if final_action == "CANNOT ASSESS":
        severity = "unknown"  # nothing was assessed, so no severity was established
    elif any(f.hard_deny for f in p_findings):
        severity = "hard_deny"
    elif final_action == "GO":
        severity = "none"
    else:
        severity = "advisory"

    # R-40: which agents were blind, not just which readings were stale
    # (R-33 covers age). A GO resting on SST alone otherwise reads
    # identically to one backed by every source. Names absence; never
    # invents a reading to fill it.
    blind_agents = [f.agent_name for f in p_findings if not f.observations]

    return Recommendation(
        id=f"rec_{uuid.uuid4().hex[:8]}",
        action=final_action,
        reason=p_decision.reason,
        recommendation=_render_text(final_action, primary_zone, final_zone, p_decision),
        chosen_zone=chosen_zone,
        overridden=overridden,
        evidence=_collect_evidence(evidence_findings),
        offline_mode=offline_mode,
        agent_findings=p_findings,
        zone_summaries=zone_summaries,
        primary_zone={"name": p_zone["name"], "lat": p_zone["lat"], "lon": p_zone["lon"]},
        severity=severity,
        blind_agents=blind_agents,
    )
