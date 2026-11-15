"""How long to wait before trying again: exponential growth, a cap, random jitter, and Retry-After respected.

One helper for every retry in the service (Gemini or Postgres outages, Phase 5 deliveries), so there is one
mechanism to reason about.
"""

import random
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from typing import Callable, Mapping

DEFAULT_BASE_SECONDS = 1.0
DEFAULT_CAP_SECONDS = 60.0


def backoff_delay(
    attempt: int,
    *,
    base: float = DEFAULT_BASE_SECONDS,
    cap: float = DEFAULT_CAP_SECONDS,
    retry_after: float | None = None,
    rng: Callable[[], float] = random.random,
) -> float:
    """Seconds to wait before retry number `attempt` (0 for the first retry).

    "Full jitter": a random value between half and all of min(cap, base * 2**attempt), so many waiting workers
    don't all retry at the same instant. A server's Retry-After is a floor: never retry sooner than it asked.
    """
    if attempt < 0:
        raise ValueError(f"attempt must be >= 0, got {attempt}")
    ceiling = min(cap, base * (2 ** min(attempt, 32)))
    delay = ceiling * (0.5 + 0.5 * rng())
    if retry_after is not None:
        delay = max(delay, retry_after)
    return delay


def retry_after_seconds(headers: Mapping[str, str] | None, *, now: datetime | None = None) -> float | None:
    """Read a Retry-After header: either seconds ("30") or an HTTP date. None if missing or unreadable."""
    if not headers:
        return None
    value = headers.get("Retry-After") or headers.get("retry-after")
    if value is None:
        return None
    value = value.strip()
    try:
        return max(0.0, float(value))
    except ValueError:
        pass
    try:
        when = parsedate_to_datetime(value)
    except (TypeError, ValueError):
        return None
    if when.tzinfo is None:
        when = when.replace(tzinfo=timezone.utc)
    return max(0.0, (when - (now or datetime.now(timezone.utc))).total_seconds())
