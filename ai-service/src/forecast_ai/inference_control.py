"""Process-local inference admission and per-request deadlines; no financial data."""

from contextlib import contextmanager
from contextvars import ContextVar
from threading import BoundedSemaphore
from time import monotonic

_deadline: ContextVar[float | None] = ContextVar("inference_deadline", default=None)
_local_slot = BoundedSemaphore(1)


class InferenceBusyError(RuntimeError):
    pass


@contextmanager
def request_budget(seconds: float):
    previous = _deadline.get()
    expires = monotonic() + seconds
    token = _deadline.set(expires if previous is None else min(previous, expires))
    try:
        yield
    finally:
        _deadline.reset(token)


def remaining_timeout(maximum: float) -> float:
    expires = _deadline.get()
    remaining = maximum if expires is None else min(maximum, expires - monotonic())
    if remaining <= 0:
        raise TimeoutError("Inference request budget exhausted")
    return remaining


@contextmanager
def local_inference_slot():
    remaining_timeout(30)
    if not _local_slot.acquire(blocking=False):
        raise InferenceBusyError("Local inference is busy")
    try:
        yield
    finally:
        _local_slot.release()
