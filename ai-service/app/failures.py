"""Sorts anything that goes wrong while investigating a case into one of two classes.

ENVIRONMENT: the world is down (Postgres unreachable, Gemini busy, rate-limited, unreachable or misconfigured).
The case is not at fault, so it is put back and retried later; it never becomes a FAILED verdict, or one outage
would flood Phase 5 with fake failures.

CASE: this case cannot be investigated (the model's output was invalid twice, the model refused, or our own code
raised). It becomes a FAILED result carrying a reason code.
"""

from dataclasses import dataclass
from enum import Enum

import httpx
import psycopg
from google.genai import errors as genai_errors

from app.agent.reasoning import ReasonerError
from app.backoff import retry_after_seconds

PROCESSING_ERROR = "PROCESSING_ERROR"
POISON_CASE = "POISON_CASE"

# Gemini replies that say "not now" or "not with this configuration", rather than "not this case".
_GEMINI_ENVIRONMENT_CODES = frozenset({401, 403, 404, 408, 429})


class FailureClass(Enum):
    ENVIRONMENT = "ENVIRONMENT"
    CASE = "CASE"


@dataclass(frozen=True)
class Failure:
    failure_class: FailureClass
    reason_code: str | None
    retry_after: float | None = None


def classify(exc: BaseException) -> Failure:
    """The class of a failure, its reason code (CASE only) and any Retry-After the server sent."""
    if isinstance(exc, ReasonerError):
        return Failure(FailureClass.CASE, exc.reason_code)
    if isinstance(exc, (psycopg.OperationalError, psycopg.InterfaceError)):
        return Failure(FailureClass.ENVIRONMENT, None)
    if isinstance(exc, genai_errors.APIError):
        code = getattr(exc, "code", None) or 0
        if code >= 500 or code in _GEMINI_ENVIRONMENT_CODES:
            response = getattr(exc, "response", None)
            headers = getattr(response, "headers", None)
            return Failure(FailureClass.ENVIRONMENT, None, retry_after_seconds(headers))
        return Failure(FailureClass.CASE, PROCESSING_ERROR)
    if isinstance(exc, (httpx.TransportError, TimeoutError, ConnectionError)):
        return Failure(FailureClass.ENVIRONMENT, None)
    return Failure(FailureClass.CASE, PROCESSING_ERROR)
