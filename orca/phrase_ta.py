"""Tamil answer phrasing. No LLM, no network, no translation service.

WHY THIS FILE EXISTS
--------------------
ORCA's users read Tamil. Until now Tamil answers came ONLY from the LLM
composer in orca/agentic.py, which means that with no key, a rate limit,
or no signal -- i.e. at sea, which is the whole point -- a Tamil speaker
got an English safety verdict. The font was vendored and precached and
rendered beautifully; the words were in the wrong language.

WHY NOT MACHINE TRANSLATION
---------------------------
Because nobody would have read it. A translated string is generated at
build time, never reviewed, and put in front of someone deciding whether
to take a boat out. If it is subtly wrong -- a negation dropped, "do not
go" rendered as "go" -- there is no test that catches it and no human in
the loop. That is a worse failure than answering in English, which is at
least honestly unhelpful.

WHAT THIS IS INSTEAD
--------------------
A CLOSED, FIXED phrasebook. Every Tamil string in this file is written
out by hand, carries its English gloss on the same line, and can be read
end to end by a Tamil speaker in a few minutes. That is the property that
matters: this is reviewable, and a translation pipeline is not.

>>> REVIEW REQUIRED, AND NOT YET DONE <<<
    These strings are Modern Standard Tamil (செந்தமிழ்), written to be
    unambiguous rather than colloquial. They have NOT been checked by a
    native speaker, and they are NOT adapted to the coastal dialects
    actually spoken in Nagapattinam, Rameswaram or Thoothukudi.
    A fisherman will understand them; they will not sound like home.

    tests/test_phrase_ta.py enforces the STRUCTURE (every action covered,
    negation present where required, no English leaking through). It
    cannot enforce that the Tamil is good. Get a native speaker to read
    docs/TAMIL_REVIEW.md before this is demoed as a Tamil product.

WHAT IS NOT TRANSLATED, AND WHY
-------------------------------
`Decision.reason` -- the agent-composed English prose ("SST 30.2 degC
within productive range, current 0.8 km/h") -- is deliberately NOT
translated here. It is free-form text assembled by five agents in
orca/agents.py; translating it would mean translating them, and a
half-translated sentence is worse than a clean one in either language.

So a Tamil answer states, in Tamil: the verdict, the zone, and the
headline readings with their units. The detailed reasoning stays
available in the evidence panel, where the content is numbers and units
and is already language-neutral. That is a real answer to the question
that was asked, which is the bar orca/phrase.py sets.
"""
from __future__ import annotations

# --- actions ------------------------------------------------------------
# The four values orca/policy.py can produce. A missing entry here would
# silently fall back to English, so tests/test_phrase_ta.py asserts the
# key set matches the policy's exactly.
ACTION = {
    "GO": "போகலாம்",                                    # "may go"
    "DO NOT GO": "போக வேண்டாம்",                        # "do not go"
    "SAFER ALTERNATIVE": "வேறு இடம் பாதுகாப்பானது",      # "another place is safer"
    "CANNOT ASSESS": "மதிப்பிட முடியவில்லை",             # "cannot assess"
}

# --- variables ----------------------------------------------------------
# Mirrors _READABLE in orca/phrase.py, key for key.
READABLE = {
    "wave_height_m": "அலை உயரம்",                       # wave height
    "wave_period_s": "அலை கால அளவு",                    # wave period
    "wave_direction_deg": "அலை திசை",                    # wave direction
    "wind_speed_kmh": "காற்றின் வேகம்",                  # wind speed
    "wind_gusts_kmh": "காற்று சுழற்சி வேகம்",             # wind gusts
    "sst_c": "கடல் நீர் வெப்பநிலை",                       # sea water temperature
    "ocean_current_velocity_kmh": "நீரோட்ட வேகம்",        # current speed
    "ocean_current_direction_deg": "நீரோட்ட திசை",        # current direction
    "rain_mm": "மழை",                                     # rain
    "precipitation_mm": "மழைப்பொழிவு",                    # precipitation
    "chlorophyll_mg_m3": "பச்சையம்",                      # chlorophyll
}

# Units are written in Tamil script rather than left as "m" / "km/h",
# because a Tamil sentence that ends in a Latin abbreviation reads as a
# machine's output. The NUMBER is never altered.
UNIT = {
    "m": "மீட்டர்",            # metre
    "km/h": "கிமீ/மணி",        # km per hour
    "kmh": "கிமீ/மணி",
    "degC": "டிகிரி செல்சியஸ்",  # degrees Celsius
    "s": "வினாடி",             # second
    "deg": "டிகிரி",           # degree
    "mm": "மி.மீ.",            # millimetre
    "mg/m3": "மி.கி/மீ³",
    "mg/m^3": "மி.கி/மீ³",
}


def unit(raw: str | None) -> str:
    return UNIT.get(raw or "", raw or "")


def readable(variable: str | None) -> str:
    return READABLE.get(variable or "", variable or "அந்த அளவீடு")  # "that reading"


def _format(value: float, raw_unit: str) -> str:
    """Same trimming rule as orca/phrase.py's _format. Display only: the
    value itself is never changed, and /evidence/{id} still serves the
    full precision the source published."""
    text = f"{value:.2f}".rstrip("0").rstrip(".")
    return f"{text} {unit(raw_unit)}".strip()


# --- verdict sentences --------------------------------------------------
# One per shape orca/planner.py's _render_text() produces. The zone name
# stays in Latin script on purpose: harbour names on charts, boat
# registrations and every sign at the landing centre are written that way,
# and transliterating them would make the answer HARDER to act on.

def verdict(action: str, primary_zone: str | None, chosen_zone: str | None) -> str:
    """The safety verdict, in Tamil, built from structured values only.

    Negation is carried by a whole clause ("போக வேண்டாம்"), never by a
    particle that could be lost in rendering or truncation. Of all the
    ways this could fail, dropping the "not" is the one that kills
    someone.
    """
    if action == "GO":
        # "You may go fishing at X."
        return f"{chosen_zone or primary_zone}-ல் மீன்பிடிக்கப் போகலாம்."

    if action == "DO NOT GO":
        # "Do not go to X. No safer place was found nearby."
        return (
            f"{primary_zone}-க்குப் போக வேண்டாம். "
            "அருகில் பாதுகாப்பான இடம் எதுவும் கிடைக்கவில்லை."
        )

    if action == "SAFER ALTERNATIVE":
        if chosen_zone and chosen_zone != primary_zone:
            # "Do not go to X. Go to Y instead, it is safer."
            return (
                f"{primary_zone}-க்குப் போக வேண்டாம். "
                f"அதற்குப் பதிலாக {chosen_zone}-க்குப் போகலாம், அது பாதுகாப்பானது."
            )
        # "Conditions at X are borderline. Go carefully, or wait."
        return (
            f"{primary_zone}-ல் நிலைமை எல்லைக்கோட்டில் உள்ளது. "
            "கவனமாகச் செல்லுங்கள், அல்லது காத்திருங்கள்."
        )

    if action == "CANNOT ASSESS":
        if chosen_zone and chosen_zone != primary_zone:
            # "ORCA has no readings for X. Go to Y instead."
            return (
                f"{primary_zone}-க்கு ORCA-விடம் அளவீடுகள் இல்லை. "
                f"அதற்குப் பதிலாக {chosen_zone}-க்குப் போகலாம்."
            )
        # The R-39 sentence. Absence of evidence must never read as safety.
        # "ORCA has no readings for X, so it cannot say anything. This does
        #  NOT mean it is safe. Do not treat it as safe."
        return (
            f"{primary_zone}-க்கு ORCA-விடம் அளவீடுகள் இல்லை, "
            "எனவே எதுவும் சொல்ல முடியாது. இது பாதுகாப்பானது என்று பொருள் அல்ல. "
            "பாதுகாப்பானது என்று எடுத்துக்கொள்ள வேண்டாம்."
        )

    # An unrecognised action must never render as permission.
    return "ORCA-வால் இப்போது முடிவு சொல்ல முடியவில்லை."  # "ORCA cannot decide now."


# --- data lookups -------------------------------------------------------

def data_lookup_sentence(lookup: dict | None, zone_name: str | None) -> str | None:
    """The number they asked for, or an honest statement that there is no
    such reading. Never a substituted one (CLAUDE.md rule 1)."""
    if not lookup:
        return None
    where = f"{zone_name}-ல் " if zone_name else ""
    if lookup.get("missing"):
        when = "நாளைக்கு " if lookup.get("time_frame") == "tomorrow" else ""
        # "ORCA has no <variable> reading for <when> at <where>."
        return f"{where}{when}{readable(lookup.get('variable'))} அளவீடு ORCA-விடம் இல்லை."
    # "The <variable> at <where> is <value> <unit>."
    return f"{where}{readable(lookup.get('variable'))} {_format(lookup['value'], lookup['unit'])}."


# --- comparisons --------------------------------------------------------

def ranking_sentence(entry: dict | None, total: int, variable: str | None,
                     wants_highest: bool) -> str | None:
    """Which END of the ranking to quote is decided by the caller, exactly
    as in orca/phrase.py -- this module does not re-read the question. A
    second place that interprets a superlative is a second place that can
    get it backwards."""
    if not entry:
        return None
    zone = entry.get("zone")
    if "value" in entry and "unit" in entry:
        # "Of the N zones ORCA covers, X has the highest/lowest <var>: <v>."
        which = "அதிக" if wants_highest else "குறைந்த"       # highest / lowest
        return (
            f"ORCA பார்க்கும் {total} இடங்களில் {zone}-ல் தான் "
            f"{which} {readable(variable)} — {_format(entry['value'], entry['unit'])}."
        )
    # Risk-ordered. The raw risk_level is deliberately never printed --
    # it is a policy output, not a MarineObservation (rule 3).
    which = "ஆபத்தானது" if wants_highest else "பாதுகாப்பானது"   # most dangerous / safest
    return (
        f"ORCA பார்க்கும் {total} இடங்களில் {zone} தான் இப்போது மிகவும் {which}."
    )


# --- everything else ----------------------------------------------------

OFF_TOPIC = (
    # "ORCA only answers questions about sea conditions and whether it is
    #  safe to go fishing. Ask about that."
    "கடல் நிலைமை பற்றியும், மீன்பிடிக்கப் போவது பாதுகாப்பானதா என்பது "
    "பற்றியும் மட்டுமே ORCA பதில் சொல்லும். அதைப் பற்றிக் கேளுங்கள்."
)

# Coverage notes. Keys match the note kinds orca/agentic.py attaches.
NOTES = {
    # "You did not name a place ORCA covers, so this answer is for X."
    "fallback": "ORCA-விடம் உள்ள இடத்தைச் சொல்லவில்லை, எனவே இந்தப் பதில் {zone}-க்கானது.",
    # "ORCA only has readings for today and tomorrow."
    "beyond": "ORCA-விடம் இன்றைக்கும் நாளைக்கும் மட்டுமே அளவீடுகள் உள்ளன, "
              "எனவே இது இன்றைய நிலைமை.",
    # "ORCA has no forecast for tomorrow, so this is today's condition."
    "stale_forecast": "நாளைய முன்னறிவிப்பு ORCA-விடம் இல்லை, "
                      "எனவே இது இன்றைய நிலைமை.",
    "route": "வழி காட்டுதல் ORCA-விடம் இல்லை.",                    # no route guidance
    "tide_or_time": "அலை நேரம் அல்லது நேர அட்டவணை ORCA-விடம் இல்லை.",  # no tide tables
    "species": "மீன் வகை பற்றிய தகவல் ORCA-விடம் இல்லை.",           # no species data
    "unit_conversion": "ஒவ்வொரு அளவீடும் அதன் மூலம் வெளியிடும் அலகிலேயே சொல்லப்படுகிறது.",
    # Offline notes, used by the mobile client.
    "offline": "இணையம் இல்லை — சேமித்து வைத்த தகவலைக் காட்டுகிறோம்.",
    "seed": "இணையம் இல்லை — செயலியுடன் வந்த தகவலைக் காட்டுகிறோம்.",
}


# --- Tamil zone names ---------------------------------------------------
#
# Someone asking in Tamil writes the harbour in Tamil script, and
# orca/planner.py's _zone_by_substring() looks for the LATIN name -- so
# "நாகப்பட்டினத்தில் இருந்து மீன்பிடிக்க போகலாமா" matched no zone at all and fell
# through to nearest-by-coordinates. The answer was right only by luck of
# where the phone happened to be.
#
# STEMS, not full names, because Tamil is agglutinative: the locative
# turns நாகப்பட்டினம் into நாகப்பட்டினத்தில் (final ம் → த்த + இல்), so a
# substring test against the dictionary form fails on the form people
# actually type. Cutting the case-bearing ending off each entry makes the
# match work for the nominative and the inflected forms alike.
#
# Matching only ever SELECTS from the ten real zones. It cannot invent a
# place, so the worst a wrong stem can do is fail to match -- which
# degrades to the existing nearest-zone fallback, disclosed as always.
ZONE_STEMS_TA = {
    "சென்னை": "Chennai",
    "கடலூர": "Cuddalore",          # கடலூர் / கடலூரில்
    "காரைக்கா": "Karaikal",         # காரைக்கால் / காரைக்காலில்
    "நாகப்பட்டின": "Nagapattinam",   # நாகப்பட்டினம் / நாகப்பட்டினத்தில்
    "கோடியக்கரை": "Point Calimere",  # the Tamil name is not a transliteration
    "மண்டப": "Mandapam",            # மண்டபம் / மண்டபத்தில்
    "ராமேஸ்வர": "Rameswaram",
    "இராமேஸ்வர": "Rameswaram",      # both spellings are current
    "தூத்துக்குடி": "Thoothukudi",
    "கன்னியாகுமரி": "Kanyakumari",
    "கொளச்ச": "Colachel",           # கொளச்சல் / கொளச்சலில்
}


def zone_by_tamil_name(query: str, zones: list[dict]) -> dict | None:
    """The zone a Tamil query names, or None.

    Longest stem first, so a stem that is a prefix of another cannot win
    over the more specific one. Same shape and same guarantees as
    _zone_by_substring(): deterministic, no network, selection only.
    """
    text = query or ""
    for stem in sorted(ZONE_STEMS_TA, key=len, reverse=True):
        if stem in text:
            name = ZONE_STEMS_TA[stem]
            for zone in zones:
                if zone["name"] == name:
                    return zone
    return None


# --- IMBL, for the Android boundary watch -------------------------------
# Spoken aloud by the phone, not read. Short sentences, the action first,
# because someone hearing this is steering a boat.
IMBL = {
    # "Danger. You are very close to the Sri Lanka sea border. Turn back now."
    "urgent": "ஆபத்து. இலங்கை கடல் எல்லைக்கு மிக அருகில் இருக்கிறீர்கள். "
              "இப்போதே திரும்பிச் செல்லுங்கள்.",
    # "Warning. The sea border is <n> km away. Turn west."
    "warning": "எச்சரிக்கை. கடல் எல்லை {km} கிலோமீட்டர் தொலைவில் உள்ளது. "
               "மேற்கு நோக்கித் திரும்புங்கள்.",
    # "The sea border is <n> km away. Be careful."
    "advisory": "கடல் எல்லை {km} கிலோமீட்டர் தொலைவில் உள்ளது. கவனமாக இருங்கள்.",
    # "You are inside the protected marine park. Fishing is not allowed here."
    "mpa": "நீங்கள் பாதுகாக்கப்பட்ட கடல் பூங்காவுக்குள் இருக்கிறீர்கள். "
           "இங்கே மீன்பிடிக்க அனுமதி இல்லை.",
}

IMBL_EN = {
    "urgent": "Danger. You are very close to the Sri Lanka maritime boundary. Turn back now.",
    "warning": "Warning. The maritime boundary is {km} km away. Turn west.",
    "advisory": "The maritime boundary is {km} km away. Be careful.",
    "mpa": "You are inside the protected marine park. Fishing is not allowed here.",
}


def render(recommendation, *, variable=None, lookup=None, ranking=None,
           wants_highest=False, coverage_notes=None, on_topic=True) -> str:
    """The Tamil answer, assembled from values the planner already
    computed. Mirrors orca/phrase.render()'s ordering exactly, including
    the rule that the verdict comes LAST and always comes."""
    if not on_topic:
        return OFF_TOPIC

    primary = recommendation.primary_zone or recommendation.chosen_zone or {}
    chosen = recommendation.chosen_zone or {}
    primary_name = primary.get("name") if isinstance(primary, dict) else None
    chosen_name = chosen.get("name") if isinstance(chosen, dict) else None

    parts: list[str] = []

    comparison = ranking_sentence(ranking, len(ranking or []), variable, wants_highest) if ranking else None
    if lookup and not comparison:
        sentence = data_lookup_sentence(lookup, chosen_name or primary_name)
        if sentence:
            parts.append(sentence)
    if comparison:
        parts.append(comparison)

    # The verdict is never buried by a narrower question.
    parts.append(verdict(recommendation.action, primary_name, chosen_name))

    for note in (coverage_notes or []):
        parts.append(note)

    return " ".join(p for p in parts if p)
