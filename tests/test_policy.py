"""Tests for orca/policy.py — the safety decision engine, written first.

This is "the demo" (war plan §8.4): a hard safety rule in code, not a
prompt. These tests exist to prove the three rules fire in strict order,
and — critically — that rule 2 (safety beats opportunity) cannot be
silently deleted without a test going red. That's the check that
distinguishes "the policy is live" from "we're demoing a hardcoded string".
"""
import inspect

import pytest

from orca.policy import Decision, Finding, resolve


def _finding(**overrides):
    base = dict(
        agent_name="ocean_state_agent",
        suggests_go=True,
        risk_level=0.1,
        hard_deny=False,
        reason="Good aggregation, warm SST",
        observations=[],
    )
    base.update(overrides)
    return Finding(**base)


# ---------------------------------------------------------------------------
# Rule 1: any hard_deny wins outright, unconditionally.
# ---------------------------------------------------------------------------

def test_hard_deny_returns_do_not_go():
    findings = [_finding(agent_name="hazard_agent", suggests_go=False,
                          risk_level=1.0, hard_deny=True, reason="Wave height 3.1m exceeds 2.5m limit")]
    decision = resolve(findings)
    assert decision.action == "DO NOT GO"
    assert "3.1" in decision.reason or "3.1" in decision.explanation


def test_hard_deny_wins_even_when_opportunity_present():
    """Rule 1 must short-circuit before rule 2 is even considered."""
    findings = [
        _finding(agent_name="ocean_state_agent", suggests_go=True, risk_level=0.1,
                 hard_deny=False, reason="Zone A strong aggregation"),
        _finding(agent_name="hazard_agent", suggests_go=False, risk_level=1.0,
                 hard_deny=True, reason="Wave height 3.1m exceeds 2.5m limit"),
    ]
    decision = resolve(findings)
    assert decision.action == "DO NOT GO"


def test_hard_deny_records_opportunity_findings_as_overridden():
    opportunity = _finding(agent_name="ocean_state_agent", suggests_go=True, risk_level=0.1)
    denial = _finding(agent_name="hazard_agent", suggests_go=False, risk_level=1.0,
                       hard_deny=True, reason="Geofenced zone")
    decision = resolve([opportunity, denial])
    assert opportunity in decision.overridden
    assert denial not in decision.overridden


def test_hard_deny_chosen_is_none():
    denial = _finding(hard_deny=True, suggests_go=False, risk_level=1.0, reason="denied")
    decision = resolve([denial])
    assert decision.chosen is None


# ---------------------------------------------------------------------------
# Rule 2: opportunity contradicted by elevated risk (>=0.6) -> safety wins.
# This is the rule that must be load-bearing.
# ---------------------------------------------------------------------------

def test_opportunity_and_elevated_risk_returns_safer_alternative():
    findings = [
        _finding(agent_name="ocean_state_agent", suggests_go=True, risk_level=0.2,
                 hard_deny=False, reason="Zone A strong aggregation, conf 0.86"),
        _finding(agent_name="hazard_agent", suggests_go=False, risk_level=0.71,
                 hard_deny=False, reason="Elevated wave height, conf 0.71"),
    ]
    decision = resolve(findings)
    assert decision.action == "SAFER ALTERNATIVE"


def test_rule_2_is_load_bearing_do_not_delete():
    """If the 'opportunity and danger' branch in resolve() is ever removed,
    this exact scenario falls through to rule 3 and wrongly returns "GO" for
    a zone that a hazard agent flagged as risky. That is the single failure
    this project cannot afford (war plan §3.1, §8.4). This test must fail
    the moment that branch disappears — do not weaken or delete it.
    """
    opportunity = _finding(agent_name="ocean_state_agent", suggests_go=True, risk_level=0.15)
    danger = _finding(agent_name="hazard_agent", suggests_go=False, risk_level=0.6,
                       hard_deny=False, reason="risk at the 0.6 boundary")
    decision = resolve([opportunity, danger])
    assert decision.action == "SAFER ALTERNATIVE", (
        "resolve() returned GO for a finding set containing both an opportunity "
        "and a risk_level>=0.6 danger. Rule 2 (safety beats opportunity) is "
        "missing or broken."
    )


def test_opportunity_marked_overridden_under_rule_2():
    opportunity = _finding(agent_name="ocean_state_agent", suggests_go=True, risk_level=0.2)
    danger = _finding(agent_name="hazard_agent", suggests_go=False, risk_level=0.65,
                       hard_deny=False, reason="risky")
    decision = resolve([opportunity, danger])
    assert opportunity in decision.overridden


def test_risk_level_exactly_at_threshold_triggers_rule_2():
    opportunity = _finding(suggests_go=True, risk_level=0.1)
    danger = _finding(agent_name="hazard_agent", suggests_go=False, risk_level=0.6,
                       hard_deny=False, reason="boundary")
    decision = resolve([opportunity, danger])
    assert decision.action == "SAFER ALTERNATIVE"


def test_risk_level_just_below_threshold_does_not_trigger_rule_2():
    opportunity = _finding(suggests_go=True, risk_level=0.1)
    below = _finding(agent_name="hazard_agent", suggests_go=False, risk_level=0.59,
                      hard_deny=False, reason="below threshold")
    decision = resolve([opportunity, below])
    assert decision.action == "GO"


def test_danger_without_opportunity_does_not_trigger_rule_2():
    danger_only = _finding(agent_name="hazard_agent", suggests_go=False, risk_level=0.9,
                            hard_deny=False, reason="risky but nobody wants to go anyway")
    decision = resolve([danger_only])
    assert decision.action != "SAFER ALTERNATIVE"


# ---------------------------------------------------------------------------
# Rule 3: otherwise -> GO.
# ---------------------------------------------------------------------------

def test_low_risk_opportunity_returns_go():
    findings = [_finding(agent_name="ocean_state_agent", suggests_go=True, risk_level=0.1,
                          reason="Good zone, wave height 1.4m")]
    decision = resolve(findings)
    assert decision.action == "GO"
    assert decision.chosen is findings[0]


def test_no_findings_suggesting_go_still_returns_go_when_no_hazard():
    findings = [_finding(agent_name="weather_agent", suggests_go=False, risk_level=0.0,
                          reason="clear skies")]
    decision = resolve(findings)
    assert decision.action == "GO"
    assert decision.chosen is None


# ---------------------------------------------------------------------------
# The flip test from §8.4: proves the policy is wired in, not hardcoded.
# ---------------------------------------------------------------------------

def test_flipping_wave_height_below_hard_deny_flips_the_decision():
    danger = _finding(agent_name="hazard_agent", suggests_go=False, risk_level=1.0,
                       hard_deny=True, reason="Wave height 3.1m exceeds 2.5m limit")
    opportunity = _finding(agent_name="ocean_state_agent", suggests_go=True, risk_level=0.1,
                            reason="Zone A strong aggregation")
    assert resolve([opportunity, danger]).action == "DO NOT GO"

    safe = _finding(agent_name="hazard_agent", suggests_go=False, risk_level=0.1,
                     hard_deny=False, reason="Wave height 1.0m, well within limit")
    assert resolve([opportunity, safe]).action == "GO"


# ---------------------------------------------------------------------------
# Validation / guard rails
# ---------------------------------------------------------------------------

def test_resolve_requires_at_least_one_finding():
    with pytest.raises(ValueError):
        resolve([])


@pytest.mark.parametrize("bad_risk", [-0.1, 1.1, 5])
def test_finding_risk_level_out_of_range_raises(bad_risk):
    with pytest.raises(ValueError, match="risk_level"):
        _finding(risk_level=bad_risk)


def test_decision_is_a_dataclass_with_expected_fields():
    d = Decision(action="GO", reason="ok", chosen=None, overridden=[], explanation="")
    assert d.action == "GO"
    assert d.overridden == []


def test_policy_module_makes_no_llm_calls():
    """CLAUDE.md rule 4: orca/policy.py contains NO LLM calls. This is the
    project's safety guarantee and must stay a pure function of its inputs.
    """
    import orca.policy as policy_module

    source = inspect.getsource(policy_module)
    forbidden = ["openai", "anthropic", "Claude(", "llm(", "chat.completions", "requests.get", "requests.post"]
    for token in forbidden:
        assert token.lower() not in source.lower(), f"policy.py must not contain {token!r}"
