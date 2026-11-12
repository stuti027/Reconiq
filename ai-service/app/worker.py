"""Runs accepted cases through the agent and delivers their results, in the background.

Two loops on daemon threads, both started with the app:

- Investigation loop: claim up to `max_concurrent` RECEIVED cases and investigate them in parallel. A finished
  case (RESOLVED / LOW_CONFIDENCE) or a case failure (FAILED with a reason code) is stored as AWAITING_DELIVERY.
  An environment failure (Postgres or Gemini down) puts the case back untouched and the loop waits, longer each
  round, until a round goes through cleanly.
- Delivery loop: POST each due result to Phase 5. A failed POST keeps the result and schedules the next try with
  backoff; a 2xx marks it DELIVERED.

Delivery is at-least-once: if Phase 5 accepts a POST but marking DELIVERED fails, the result is sent again later,
and Phase 5 is expected to ignore a case_id it already has.
"""

import logging
import random
import threading
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from typing import Callable

import psycopg

from app.agent.state import InvestigationOutcome
from app.backoff import backoff_delay, retry_after_seconds
from app.contracts import InvestigationRequest
from app.failures import PROCESSING_ERROR, Failure, FailureClass, classify
from app.jobs import (
    ClaimedCase,
    RecoveryReport,
    claim_cases,
    due_deliveries,
    mark_delivered,
    mark_delivery_failed,
    recover_after_crash,
    release_case,
    store_result,
)
from app.logging_setup import case_context
from app.result import InvestigationResult, failed_result, result_from_outcome

log = logging.getLogger(__name__)

Investigate = Callable[[InvestigationRequest], InvestigationOutcome]
Deliver = Callable[[InvestigationResult], None]


@dataclass(frozen=True)
class RoundReport:
    claimed: int
    environment_failure: Failure | None


class WorkerCounters:
    """In-process counts since start, for /metrics."""

    _NAMES = ("results_stored", "case_failures", "environment_failures", "deliveries", "delivery_failures")

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._values = dict.fromkeys(self._NAMES, 0)

    def add(self, name: str, n: int = 1) -> None:
        with self._lock:
            self._values[name] += n

    def snapshot(self) -> dict[str, int]:
        with self._lock:
            return dict(self._values)


class Worker:
    def __init__(
        self,
        *,
        connect: Callable[[], psycopg.Connection],
        investigate: Investigate,
        deliver: Deliver,
        model: str,
        max_concurrent: int = 2,
        max_pickups: int = 3,
        poll_seconds: float = 2.0,
        backoff_base: float = 1.0,
        backoff_cap: float = 60.0,
        delivery_batch: int = 20,
        rng: Callable[[], float] = random.random,
    ) -> None:
        self._connect = connect
        self._investigate = investigate
        self._deliver = deliver
        self.model = model
        self._max_concurrent = max_concurrent
        self._max_pickups = max_pickups
        self._poll = poll_seconds
        self._base = backoff_base
        self._cap = backoff_cap
        self._batch = delivery_batch
        self._rng = rng
        self.counters = WorkerCounters()
        self._unreleased: set[str] = set()
        self._unreleased_lock = threading.Lock()
        self._stop = threading.Event()
        self._threads: list[threading.Thread] = []

    # ---- one round each, used by the loops and by tests -------------------------------------------------

    def recover(self) -> RecoveryReport:
        """The startup sweep (see jobs.recover_after_crash)."""
        with self._connect() as conn:
            report = recover_after_crash(conn, max_pickups=self._max_pickups, model=self.model)
        if report.reset or report.poisoned:
            log.warning("startup recovery: %d case(s) put back, %d marked POISON_CASE", len(report.reset), len(report.poisoned))
        return report

    def investigate_once(self) -> RoundReport:
        """Claim up to max_concurrent cases and investigate them in parallel."""
        try:
            self._release_leftovers()
            with self._connect() as conn:
                claimed = claim_cases(conn, self._max_concurrent)
        except Exception as exc:
            failure = classify(exc)
            if failure.failure_class is FailureClass.CASE:
                log.exception("could not claim cases")
            else:
                log.warning("could not claim cases: %s", type(exc).__name__)
            self.counters.add("environment_failures")
            return RoundReport(0, Failure(FailureClass.ENVIRONMENT, None, failure.retry_after))
        if not claimed:
            return RoundReport(0, None)

        with ThreadPoolExecutor(max_workers=len(claimed), thread_name_prefix="investigation") as pool:
            failures = [f for f in pool.map(self._process, claimed) if f is not None]
        if not failures:
            return RoundReport(len(claimed), None)
        waits = [f.retry_after for f in failures if f.retry_after is not None]
        return RoundReport(len(claimed), Failure(FailureClass.ENVIRONMENT, None, max(waits) if waits else None))

    def deliver_once(self) -> int:
        """POST every due result once. Returns how many were delivered."""
        try:
            with self._connect() as conn:
                due = due_deliveries(conn, self._batch)
        except Exception as exc:
            log.warning("could not read pending deliveries: %s", type(exc).__name__)
            return 0

        delivered = 0
        for item in due:
            with case_context(item.case_id):
                try:
                    self._deliver(item.result)
                except Exception as exc:
                    headers = getattr(getattr(exc, "response", None), "headers", None)
                    delay = self._delay(item.delivery_attempts, retry_after_seconds(headers))
                    log.warning("delivery to Phase 5 failed (%s), next try in %.0f s", _describe(exc), delay)
                    self.counters.add("delivery_failures")
                    try:
                        with self._connect() as conn:
                            mark_delivery_failed(conn, item.case_id, delay_seconds=delay, error=_describe(exc))
                    except Exception as db_exc:
                        log.warning("could not record the failed delivery: %s", type(db_exc).__name__)
                    continue
                try:
                    with self._connect() as conn:
                        mark_delivered(conn, item.case_id)
                except Exception as db_exc:
                    log.warning("delivered, but not recorded (%s); it will be sent again", type(db_exc).__name__)
                    continue
                log.info("delivered to Phase 5 (%s)", item.result.status)
                self.counters.add("deliveries")
                delivered += 1
        return delivered

    # ---- background loops -------------------------------------------------------------------------------

    def start(self) -> None:
        self._stop.clear()
        self._threads = [
            threading.Thread(target=self._investigation_loop, name="investigation-loop", daemon=True),
            threading.Thread(target=self._delivery_loop, name="delivery-loop", daemon=True),
        ]
        for thread in self._threads:
            thread.start()

    def stop(self, timeout: float = 10.0) -> None:
        self._stop.set()
        for thread in self._threads:
            thread.join(timeout)
        self._threads = []

    def _investigation_loop(self) -> None:
        streak = 0
        while not self._stop.is_set():
            try:
                report = self.investigate_once()
            except Exception:
                log.exception("investigation round failed")
                report = RoundReport(0, Failure(FailureClass.ENVIRONMENT, None))
            if report.environment_failure is not None:
                wait = self._delay(streak, report.environment_failure.retry_after)
                streak += 1
                log.warning("environment problem, pausing investigations for %.0f s", wait)
            else:
                streak = 0
                wait = 0.0 if report.claimed else self._poll
            self._stop.wait(wait)

    def _delivery_loop(self) -> None:
        while not self._stop.is_set():
            try:
                delivered = self.deliver_once()
            except Exception:
                log.exception("delivery round failed")
                delivered = 0
            self._stop.wait(0.0 if delivered >= self._batch else self._poll)

    # ---- one case -----------------------------------------------------------------------------------------

    def _process(self, case: ClaimedCase) -> Failure | None:
        """Investigate one claimed case. Returns the failure if it was an environment problem, else None."""
        request = case.request
        with case_context(request.case_id):
            log.info("investigating (pickup %d)", case.pickup_count)
            try:
                result = result_from_outcome(request, self._investigate(request))
            except Exception as exc:
                failure = classify(exc)
                if failure.failure_class is FailureClass.ENVIRONMENT:
                    log.warning("environment problem (%s), case put back", type(exc).__name__)
                    self.counters.add("environment_failures")
                    self._release(request.case_id)
                    return failure
                if failure.reason_code == PROCESSING_ERROR:
                    log.exception("unexpected error, case FAILED")
                else:
                    log.warning("case FAILED: %s", failure.reason_code)
                self.counters.add("case_failures")
                result = failed_result(request, failure_reason=failure.reason_code, model=self.model)

            try:
                with self._connect() as conn:
                    stored = store_result(conn, result)
            except Exception as exc:
                log.warning("result not stored (%s), case will be retried", type(exc).__name__)
                self.counters.add("environment_failures")
                self._release(request.case_id)
                return Failure(FailureClass.ENVIRONMENT, None)
            if stored:
                log.info("result stored: %s", result.status)
                self.counters.add("results_stored")
            else:
                log.warning("result not stored: the case was no longer in progress")
            return None

    def _release(self, case_id: str) -> None:
        try:
            with self._connect() as conn:
                release_case(conn, case_id)
        except Exception as exc:
            log.warning("could not put the case back (%s); will retry", type(exc).__name__)
            with self._unreleased_lock:
                self._unreleased.add(case_id)

    def _release_leftovers(self) -> None:
        """Cases that could not be put back during an outage are put back as soon as the database answers."""
        with self._unreleased_lock:
            pending = list(self._unreleased)
        for case_id in pending:
            with self._connect() as conn:
                release_case(conn, case_id)
            with self._unreleased_lock:
                self._unreleased.discard(case_id)

    def _delay(self, attempt: int, retry_after: float | None) -> float:
        return backoff_delay(attempt, base=self._base, cap=self._cap, retry_after=retry_after, rng=self._rng)


def _describe(exc: BaseException) -> str:
    response = getattr(exc, "response", None)
    status = getattr(response, "status_code", None)
    return f"{type(exc).__name__} {status}" if status else type(exc).__name__
