"""Tamil answers with no model, no network, no translation service.

WHAT THESE TESTS CAN AND CANNOT DO
----------------------------------
They enforce STRUCTURE: that every action ORCA can produce has a Tamil
rendering, that the negation is present where a life depends on it, that
no English leaks into a Tamil answer, and that a Tamil question resolves
the zone it names.

They CANNOT tell you the Tamil is good. No test can. orca/phrase_ta.py
carries a standing REVIEW REQUIRED notice for exactly that reason, and
docs/TAMIL_REVIEW.md is the thing a native speaker signs off. Treat a
green run here as "the wiring is right", never as "the language is right".
"""
from __future__ import annotations

import re

import pytest

from data.fetch import ZONES
from orca import phrase_ta

# Any Latin letter in a Tamil sentence, EXCEPT inside a zone name or the
# product name. Zone names deliberately stay in Latin script: harbour
# names on charts, boat registrations and every sign at the landing centre
# are written that way, so transliterating them makes the answer harder to
# act on, not easier.
_ZONE_NAMES = [z["name"] for z in ZONES]


def _strip_allowed_latin(text: str) -> str:
    for name in sorted(_ZONE_NAMES, key=len, reverse=True):
        text = text.replace(name, "")
    return text.replace("ORCA", "")


def _has_english_words(text: str) -> bool:
    """Two or more consecutive Latin letters that are not a zone or ORCA."""
    return bool(re.search(r"[A-Za-z]{2,}", _strip_allowed_latin(text)))


# --- coverage of the policy's outputs -----------------------------------

def test_every_action_policy_can_produce_has_a_tamil_rendering():
    """A missing key would fall through to English silently, which is the
    exact failure this module exists to remove."""
    from orca import policy
    produced = {"GO", "DO NOT GO", "SAFER ALTERNATIVE", "CANNOT ASSESS"}
    assert set(phrase_ta.ACTION) == produced
    for action in produced:
        text = phrase_ta.verdict(action, "Mandapam", "Karaikal")
        assert text and not _has_english_words(text), f"{action}: {text}"


def test_an_unrecognised_action_never_renders_as_permission():
    """Same non-permissive default as actionClass() in the web client and
    ACTION_COLOR in the 3D view."""
    text = phrase_ta.verdict("SOMETHING NEW", "Mandapam", "Mandapam")
    assert phrase_ta.ACTION["GO"] not in text


# --- the negation, which is the part that kills people ------------------

def test_do_not_go_carries_an_explicit_negation():
    text = phrase_ta.verdict("DO NOT GO", "Mandapam", None)
    assert "வேண்டாம்" in text            # "do not"
    assert "போகலாம்" not in text          # never "you may go"


def test_safer_alternative_says_do_not_go_there_and_names_where_to_go():
    text = phrase_ta.verdict("SAFER ALTERNATIVE", "Mandapam", "Karaikal")
    assert "வேண்டாம்" in text
    assert "Mandapam" in text and "Karaikal" in text


def test_cannot_assess_never_reads_as_safe():
    """R-39 in Tamil: absence of evidence must not be reported as safety.
    The English sentence says "this is not a judgement that conditions are
    safe"; the Tamil has to say it too, or the two clients disagree about
    the most dangerous sentence in the product."""
    text = phrase_ta.verdict("CANNOT ASSESS", "Mandapam", None)
    assert "பாதுகாப்பானது என்று பொருள் அல்ல" in text   # "does not mean it is safe"
    assert "போகலாம்" not in text


# --- no English leaks ---------------------------------------------------

@pytest.mark.parametrize("text", [
    phrase_ta.OFF_TOPIC,
    *phrase_ta.NOTES.values(),
    *phrase_ta.IMBL.values(),
])
def test_no_english_leaks_into_a_tamil_string(text):
    sample = text.format(zone="Mandapam", km="4") if "{" in text else text
    assert not _has_english_words(sample), sample


def test_every_lookup_variable_has_a_tamil_name():
    """Mirrors orca/phrase.py's _READABLE key for key: a variable ORCA can
    look up but cannot name in Tamil would render its identifier."""
    from orca import memory
    for variable in memory.LOOKUP_VARIABLES:
        assert variable in phrase_ta.READABLE, variable
        assert not _has_english_words(phrase_ta.READABLE[variable])


# --- numbers ------------------------------------------------------------

def test_a_reading_states_the_number_and_a_tamil_unit():
    text = phrase_ta.data_lookup_sentence(
        {"variable": "wave_height_m", "value": 1.6, "unit": "m"}, "Thoothukudi")
    assert "1.6" in text
    assert "மீட்டர்" in text          # the unit is not left as "m"
    assert "Thoothukudi" in text


def test_a_missing_reading_is_stated_not_substituted():
    """CLAUDE.md rule 1 does not weaken in translation."""
    text = phrase_ta.data_lookup_sentence(
        {"variable": "chlorophyll_mg_m3", "time_frame": "tomorrow", "missing": True},
        "Karaikal")
    assert "இல்லை" in text            # "there is none"
    assert not re.search(r"\d", text)  # and no number appears at all


def test_the_value_is_never_altered_only_trimmed():
    assert phrase_ta._format(2.0, "m").startswith("2 ")
    assert phrase_ta._format(1.60, "m").startswith("1.6 ")
    assert phrase_ta._format(0.725, "m").startswith("0.72 ")


def test_a_risk_ranking_never_prints_the_raw_risk_number():
    """risk_level is a policy output, not a MarineObservation, and rule 3
    governs every number a user sees -- in either language."""
    text = phrase_ta.ranking_sentence(
        {"zone": "Mandapam", "action": "SAFER ALTERNATIVE"}, 10, None, True)
    assert "Mandapam" in text
    assert not re.search(r"0\.\d", text)


def test_a_comparison_quotes_the_end_the_caller_asked_for():
    """The superlative is read ONCE, in orca/phrase.py. A second place
    interpreting it is a second place that can get it backwards."""
    high = phrase_ta.ranking_sentence(
        {"zone": "Thoothukudi", "value": 1.6, "unit": "m"}, 10, "wave_height_m", True)
    low = phrase_ta.ranking_sentence(
        {"zone": "Cuddalore", "value": 0.4, "unit": "m"}, 10, "wave_height_m", False)
    assert "அதிக" in high and "குறைந்த" in low


# --- Tamil zone names ---------------------------------------------------

@pytest.mark.parametrize("query,expected", [
    # Nominative and locative forms of the same name. Tamil is
    # agglutinative, so the inflected form is what people actually type.
    ("நாகப்பட்டினம்", "Nagapattinam"),
    ("நாகப்பட்டினத்தில் இருந்து மீன்பிடிக்க போகலாமா", "Nagapattinam"),
    ("மண்டபத்தில் பாதுகாப்பானதா", "Mandapam"),
    ("தூத்துக்குடியில் அலை உயரம்", "Thoothukudi"),
    ("இராமேஸ்வரத்தில் நாளை", "Rameswaram"),
    ("ராமேஸ்வரம்", "Rameswaram"),
    ("சென்னையில் காற்று", "Chennai"),
    ("கொளச்சலில் மீன்", "Colachel"),
])
def test_a_tamil_query_resolves_the_zone_it_names(query, expected):
    zone = phrase_ta.zone_by_tamil_name(query, ZONES)
    assert zone is not None, f"no match for {query!r}"
    assert zone["name"] == expected


def test_a_tamil_query_naming_no_zone_matches_nothing():
    """Selection only. It must never invent a place -- failing to match
    degrades to the disclosed nearest-zone fallback, which is correct."""
    assert phrase_ta.zone_by_tamil_name("இன்று கடல் எப்படி இருக்கிறது?", ZONES) is None


def test_every_tamil_stem_maps_to_a_real_zone():
    """A typo in the alias table would silently point at nothing."""
    names = {z["name"] for z in ZONES}
    for stem, name in phrase_ta.ZONE_STEMS_TA.items():
        assert name in names, f"{stem} -> {name} is not a real zone"


def test_all_ten_zones_are_reachable_in_tamil():
    covered = set(phrase_ta.ZONE_STEMS_TA.values())
    assert covered == {z["name"] for z in ZONES}


# --- end to end, with no key --------------------------------------------

def test_a_tamil_question_gets_a_tamil_answer_with_no_model(monkeypatch):
    """The whole point. Before orca/phrase_ta.py, this returned English."""
    monkeypatch.delenv("GROQ_API_KEY", raising=False)
    monkeypatch.delenv("ORCA_LLM_API_KEY", raising=False)
    from orca.agentic import answer_question
    from orca.planner import load_cached_observations

    observations = load_cached_observations()
    if not observations:
        pytest.skip("no cached observations")
    rec = answer_question("மண்டபத்தில் மீன்பிடிக்க பாதுகாப்பானதா?", 13.12, 80.29,
                          observations=observations, offline_mode=True)
    assert rec.detected_language == "ta"
    # Resolved from the Tamil NAME, not from the coordinates -- those are
    # Chennai's, 500 km away.
    assert rec.zone_match == "exact"
    assert rec.primary_zone["name"] == "Mandapam"
    assert not _has_english_words(rec.recommendation), rec.recommendation
