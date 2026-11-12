"""Logging for the service: every line carries the case it belongs to, and secrets are masked.

Inside `with case_context(case_id):` every log line from that thread shows `case=<case_id>`; outside it shows
`case=-`. Any configured secret (the Gemini key, the database password) is replaced by `***` if it ever appears
in a message. Prompts and evidence contain financial data, so they may only be logged at DEBUG.
"""

import logging
import sys
from contextlib import contextmanager
from contextvars import ContextVar
from typing import Iterable, Iterator

LOG_FORMAT = "%(asctime)s %(levelname)s case=%(case_id)s %(name)s: %(message)s"

_case_id: ContextVar[str] = ContextVar("case_id", default="-")
_HANDLER_NAME = "ai-service"


class CaseIdFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        record.case_id = _case_id.get()
        return True


class SecretFilter(logging.Filter):
    """Replaces each secret value with *** in the final message."""

    def __init__(self, secrets: Iterable[str]) -> None:
        super().__init__()
        self._secrets = [s for s in secrets if s]

    def filter(self, record: logging.LogRecord) -> bool:
        if self._secrets:
            message = record.getMessage()
            masked = message
            for secret in self._secrets:
                masked = masked.replace(secret, "***")
            if masked != message:
                record.msg, record.args = masked, None
        return True


@contextmanager
def case_context(case_id: str) -> Iterator[None]:
    token = _case_id.set(case_id)
    try:
        yield
    finally:
        _case_id.reset(token)


def current_case_id() -> str:
    return _case_id.get()


def configure_logging(level: str = "INFO", secrets: Iterable[str] = (), stream=None) -> logging.Handler:
    """Install one handler on the root logger (replacing a previous one from this function). Returns it."""
    root = logging.getLogger()
    for handler in list(root.handlers):
        if handler.get_name() == _HANDLER_NAME:
            root.removeHandler(handler)
    handler = logging.StreamHandler(stream or sys.stderr)
    handler.set_name(_HANDLER_NAME)
    handler.setFormatter(logging.Formatter(LOG_FORMAT))
    handler.addFilter(CaseIdFilter())
    handler.addFilter(SecretFilter(secrets))
    root.addHandler(handler)
    root.setLevel(level)
    return handler
