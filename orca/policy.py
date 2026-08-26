"""The safety decision engine. War plan §8.4: "THIS IS THE DEMO."

Deterministic Python only. No LLM calls, no network calls, no randomness.
A safety rule that can be prompted out of a decision isn't a safety rule
(CLAUDE.md rule 4) — this module is the project's guarantee that it can't
be, and it must stay unit-testable to prove it.

resolve() is scoped to "should you go to the place these findings describe":
it does not know about alternative zones. Comparing zones and assembling a
final multi-zone recommendation is orca/planner.py's job, not this file's.
"""
from dataclasses import dataclass, field

from orca.schema import MarineObservation

RISK_OVERRIDE_THRESHOLD = 0.6


@dataclass
class Finding:
    agent_name: str
    suggests_go: bool
    risk_level: float  # 0-1
    hard_deny: bool
    reason: str
    observations: list[MarineObservation] = field(default_factory=list)

    def __post_init__(self) -> None:
        if not (0.0 <= self.risk_level <= 1.0):
            raise ValueError(f"Finding.risk_level must be between 0 and 1, got {self.risk_level}")
        if not self.agent_name:
            raise ValueError("Finding.agent_name is required and cannot be empty")


@dataclass
class Decision:
    action: str  # "GO" | "DO NOT GO" | "SAFER ALTERNATIVE"
    reason: str
    chosen: Finding | None = None
    overridden: list[Finding] = field(default_factory=list)
    explanation: str = ""


def resolve(findings: list[Finding]) -> Decision:
    if not findings:
        raise ValueError("resolve() requires at least one Finding; refusing to decide on no evidence")

    # Rule 1: any hard denial wins outright, unconditionally.
    hard_denials = [f for f in findings if f.hard_deny]
    if hard_denials:
        primary = hard_denials[0]
        return Decision(
            action="DO NOT GO",
            reason=primary.reason,
            chosen=None,
            overridden=[f for f in findings if f.suggests_go],
            explanation=f"Hard safety denial from {primary.agent_name}: {primary.reason}",
        )

    opportunity = [f for f in findings if f.suggests_go]
    danger = [f for f in findings if f.risk_level >= RISK_OVERRIDE_THRESHOLD]

    # Rule 2: opportunity contradicted by elevated risk -> safety wins.
    if opportunity and danger:
        primary_danger = danger[0]
        return Decision(
            action="SAFER ALTERNATIVE",
            reason=primary_danger.reason,
            chosen=None,
            overridden=opportunity,
            explanation=f"Opportunity overridden by hazard: {primary_danger.reason}",
        )

    # Rule 3: otherwise, go.
    chosen = opportunity[0] if opportunity else None
    return Decision(
        action="GO",
        reason=chosen.reason if chosen else "No hazards found; conditions acceptable",
        chosen=chosen,
        overridden=[],
        explanation="",
    )
