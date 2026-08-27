"""The one type allowed to carry a number to a user.

CLAUDE.md rule 3: every number shown to a user must be a MarineObservation
carrying source, valid_time, confidence and provenance. Bare floats are
dropped. The validation here is what makes that a guarantee instead of a
convention.
"""
from dataclasses import dataclass
from datetime import datetime


@dataclass
class MarineObservation:
    variable: str
    value: float
    unit: str
    lat: float
    lon: float
    valid_time: datetime
    fetched_at: datetime
    source: str
    confidence: float
    freshness_min: int
    provenance: str

    def __post_init__(self) -> None:
        if not self.source:
            raise ValueError("MarineObservation.source is required and cannot be empty")
        if not isinstance(self.valid_time, datetime):
            raise ValueError("MarineObservation.valid_time must be a datetime")
        if not isinstance(self.fetched_at, datetime):
            raise ValueError("MarineObservation.fetched_at must be a datetime")
        if not self.provenance:
            raise ValueError("MarineObservation.provenance is required and cannot be empty")
        if not (0.0 <= self.confidence <= 1.0):
            raise ValueError(
                f"MarineObservation.confidence must be between 0 and 1, got {self.confidence}"
            )

    def to_dict(self) -> dict:
        return {
            "variable": self.variable,
            "value": self.value,
            "unit": self.unit,
            "lat": self.lat,
            "lon": self.lon,
            "valid_time": self.valid_time.isoformat(),
            "fetched_at": self.fetched_at.isoformat(),
            "source": self.source,
            "confidence": self.confidence,
            "freshness_min": self.freshness_min,
            "provenance": self.provenance,
        }
