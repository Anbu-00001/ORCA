"""The deterministic floor: understanding and answering with no model.

These two modules exist because of a measured failure. With GROQ_API_KEY
removed, ORCA classified EVERY question as intent=verdict, time_frame=now,
language=en -- so it could answer exactly one question ("is it safe
here?") and answered everything else with that same sentence. A Groq
rate limit on stage looked identical, which is why the obvious fix for it
looked like it had to be a second API key.

Every test here runs with no key, no network and no model.
"""
from __future__ import annotations

import pytest

from data.fetch import ZONES
from orca import phrase
from orca.extract import detect_language, extract


# --- language: exact, not probabilistic ---------------------------------
@pytest.mark.parametrize("query,expected", [
    ("Is it safe to fish today?", "en"),
    ("இன்று மீன்பிடிக்க பாதுகாப்பானதா?", "ta"),
    ("நாகப்பட்டினத்தில் இருந்து மீன்பிடிக்க போகலாமா", "ta"),
    # Mixed script is a Tamil question: answering a Tamil speaker in
    # English is the worse of the two possible mistakes here.
    ("Rameswaram-ல பாதுகாப்பானதா?", "ta"),
    ("", "en"),
])
def test_language_detection_is_exact(query, expected):
    """Tamil is one contiguous Unicode block (U+0B80..U+0BFF), so this is
    a range check, not a guess -- no model, no library, no training data."""
    assert detect_language(query) == expected


# --- intent and variable ------------------------------------------------
@pytest.mark.parametrize("query,intent,variable", [
    ("How high are the waves at Chennai?", "data_lookup", "wave_height_m"),
    ("What is the wind at Thoothukudi?", "data_lookup", "wind_speed_kmh"),
    ("what's the sea temperature?", "data_lookup", "sst_c"),
    # Terse noun-phrase questions are still data requests.
    ("waves tomorrow at Nagapattinam?", "data_lookup", "wave_height_m"),
    ("chlorophyll?", "data_lookup", "chlorophyll_mg_m3"),
    # A verdict phrase WINS over a bare variable mention. Of the two
    # possible mistakes, answering a safety question with a bare
    # measurement is the one that matters.
    ("is it safe with these waves?", "verdict", None),
    ("should I go out, the wind is up?", "verdict", None),
    ("Is it safe near Rameswaram?", "verdict", None),
])
def test_intent_and_variable(query, intent, variable):
    got = extract(query, ZONES)
    assert got["intent"] == intent
    assert got["variable"] == variable


def test_a_curly_apostrophe_is_handled_like_a_straight_one():
    """Phone keyboards produce U+2019. Splitting "what's" into "what s"
    matched no lookup phrase and silently downgraded every contraction to
    a verdict -- which is how this was found."""
    for q in ("what's the wind?", "what’s the wind?", "whats the wind?"):
        assert extract(q, ZONES)["intent"] == "data_lookup", q


# --- time frame, scope, unsupported -------------------------------------
@pytest.mark.parametrize("query,expected", [
    ("Is it safe today?", "now"),
    ("What about tomorrow?", "tomorrow"),
    ("நாளை பாதுகாப்பானதா?", "tomorrow"),
    ("What about the day after tomorrow?", "beyond"),
    ("How about next week?", "beyond"),
])
def test_time_frame(query, expected):
    assert extract(query, ZONES)["time_frame"] == expected


@pytest.mark.parametrize("query,scope", [
    ("Which place has the worst waves?", "all_zones"),
    ("Where is the safest place to fish?", "all_zones"),
    ("Is Kanyakumari safer than Rameswaram?", "all_zones"),
    ("Is it safe near Rameswaram?", "one_zone"),
])
def test_scope(query, scope):
    assert extract(query, ZONES)["scope"] == scope


def test_naming_two_real_zones_is_a_comparison():
    got = extract("Compare Chennai and Thoothukudi", ZONES)
    assert got["scope"] == "all_zones"
    assert got["unsupported"] == "second_zone"


@pytest.mark.parametrize("query,kind", [
    ("How do I get to Karaikal?", "route"),
    ("What time is high tide?", "tide_or_time"),
    ("Will I catch seer fish?", "species"),
    ("Give me the wave height in feet", "unit_conversion"),
    ("Is it safe near Rameswaram?", "none"),
])
def test_unsupported_capabilities_are_named(query, kind):
    assert extract(query, ZONES)["unsupported"] == kind


@pytest.mark.parametrize("query,on_topic", [
    ("What is the capital of France?", False),
    ("Write me a python script", False),
    ("Is it safe to fish today?", True),
    # Marine words rescue a question that trips an off-topic term.
    ("Who is going out to sea today?", True),
])
def test_off_topic_detection(query, on_topic):
    assert extract(query, ZONES)["on_topic"] is on_topic


def test_zone_inference_is_left_to_the_model():
    """The one job keywords genuinely cannot do. orca/agentic.py matches
    a literal zone name itself first, and only calls the model when that
    fails -- which is what halves LLM usage."""
    assert extract("is it safe at the southernmost tip of India?", ZONES)["zone_name"] is None


def test_every_field_is_inside_its_closed_set():
    """The whole premise: these are lookups, not inferences. Anything
    outside the enum would reach the composer unvalidated."""
    from orca import agentic, memory
    for query in ("waves tomorrow?", "which is safest?", "how do I get there?",
                  "இன்று பாதுகாப்பானதா?", "what is the capital of France?"):
        got = extract(query, ZONES)
        assert got["language"] in ("en", "ta")
        assert got["intent"] in ("verdict", "data_lookup")
        assert got["time_frame"] in agentic.EXTRACTION_TIME_FRAMES
        assert got["scope"] in agentic.SCOPES
        assert got["unsupported"] in agentic.UNSUPPORTED_KINDS
        assert got["variable"] is None or got["variable"] in memory.LOOKUP_VARIABLES
        assert isinstance(got["on_topic"], bool)


# --- phrasing -----------------------------------------------------------
class _Rec:
    def __init__(self, text="Go to Chennai.", zone="Chennai"):
        self.recommendation = text
        self.chosen_zone = {"name": zone}
        self.primary_zone = {"name": zone}


def _render(rec=None, **kw):
    kw.setdefault("query", "q")
    kw.setdefault("intent", "verdict")
    kw.setdefault("variable", None)
    kw.setdefault("lookup", None)
    kw.setdefault("ranking", None)
    kw.setdefault("coverage_note", None)
    kw.setdefault("on_topic", True)
    return phrase.render(rec or _Rec(), **kw)


def test_a_data_lookup_states_the_number():
    text = _render(intent="data_lookup", variable="wave_height_m",
                   lookup={"variable": "wave_height_m", "value": 0.72, "unit": "m"})
    assert "wave height at Chennai is 0.72 m" in text


def test_a_missing_reading_is_stated_not_substituted():
    """CLAUDE.md rule 1: absent is a correct answer, invented is not."""
    text = _render(intent="data_lookup", variable="chlorophyll_mg_m3",
                   lookup={"variable": "chlorophyll_mg_m3", "time_frame": "tomorrow",
                           "missing": True})
    assert "no chlorophyll reading" in text
    assert "for tomorrow" in text


def test_the_verdict_is_never_buried_by_a_narrower_question():
    """Someone who asked only for a wave height still has to be told the
    sea is over the limit."""
    rec = _Rec(text="Do not go to Chennai — waves exceed the 2.5 m limit.")
    text = _render(rec, intent="data_lookup", variable="wave_height_m",
                   lookup={"variable": "wave_height_m", "value": 2.9, "unit": "m"})
    assert "2.9 m" in text and "Do not go" in text


@pytest.mark.parametrize("query,expected_zone", [
    ("Which place has the worst waves?", "Thoothukudi"),
    ("Where are the waves lowest?", "Cuddalore"),
])
def test_a_comparison_quotes_the_right_end_of_the_ranking(query, expected_zone):
    """_rank_zones() returns WORST first. Asked for the calmest and handed
    the worst, the answer would be exactly backwards -- and pointed at
    danger rather than away from it."""
    ranking = [{"zone": "Thoothukudi", "value": 1.6, "unit": "m"},
               {"zone": "Chennai", "value": 0.7, "unit": "m"},
               {"zone": "Cuddalore", "value": 0.4, "unit": "m"}]
    text = _render(query=query, variable="wave_height_m", ranking=ranking)
    assert expected_zone in text


def test_a_risk_ranking_never_prints_the_raw_risk_number():
    """risk_level is a policy output, not a MarineObservation, and rule 3
    governs every number a user sees."""
    ranking = [{"zone": "Mandapam", "action": "SAFER ALTERNATIVE"},
               {"zone": "Karaikal", "action": "GO"}]
    text = _render(query="where is the calmest place?", ranking=ranking)
    assert "Karaikal" in text and "GO" in text
    assert "0." not in text


def test_a_comparison_does_not_also_lead_with_the_anchor_zone():
    """Rameswaram is where the question was asked FROM, not what was asked
    about -- leading with its own reading is noise."""
    ranking = [{"zone": "Thoothukudi", "value": 1.6, "unit": "m"},
               {"zone": "Chennai", "value": 0.7, "unit": "m"}]
    text = _render(_Rec(zone="Rameswaram"), query="which place has the worst waves?",
                   intent="data_lookup", variable="wave_height_m",
                   lookup={"variable": "wave_height_m", "value": 1.38, "unit": "m"},
                   ranking=ranking)
    assert "1.38" not in text
    assert "Thoothukudi" in text


def test_off_topic_gets_a_scope_statement_and_no_readings():
    text = _render(on_topic=False)
    assert "only answers questions about sea conditions" in text
    assert "Chennai" not in text


def test_a_coverage_note_is_always_carried_through():
    text = _render(coverage_note="ORCA has no forecast cached for tomorrow.")
    assert "no forecast cached for tomorrow" in text


def test_a_trailing_zero_is_trimmed_without_changing_the_value():
    assert phrase._format(2.0, "m") == "2 m"
    assert phrase._format(1.60, "m") == "1.6 m"
    assert phrase._format(0.725, "m") == "0.72 m"
