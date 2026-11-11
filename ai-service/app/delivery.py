"""Sends a finished investigation result to Phase 5.

One POST per call, to the URL in PHASE5_RESULT_URL. A delivery that fails raises: a non-2xx reply raises
httpx.HTTPStatusError, a network problem raises httpx's own error. Retrying and keeping undelivered results belong
to the worker's failure handling, not here.
"""

import os

import httpx

from app.result import InvestigationResult

RESULT_URL_ENV = "PHASE5_RESULT_URL"
DEFAULT_TIMEOUT_SECONDS = 10.0


class DeliveryConfigError(RuntimeError):
    """No Phase 5 URL was given and PHASE5_RESULT_URL is not set."""


def deliver_result(
    result: InvestigationResult,
    *,
    url: str | None = None,
    client: httpx.Client | None = None,
    timeout: float = DEFAULT_TIMEOUT_SECONDS,
) -> None:
    """POST the result as JSON. With client given, the caller owns it (and its timeout)."""
    target = url or os.environ.get(RESULT_URL_ENV)
    if not target:
        raise DeliveryConfigError(f"{RESULT_URL_ENV} is not set")

    body = result.model_dump(mode="json")
    if client is not None:
        response = client.post(target, json=body)
    else:
        with httpx.Client(timeout=timeout) as own_client:
            response = own_client.post(target, json=body)
    response.raise_for_status()
