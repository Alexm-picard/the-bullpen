"""Structured logging baseline using structlog.

LOG_FORMAT=json switches to JSON output for production / log shipping.
Default stays human-readable for local dev. Correlation IDs ride on
contextvars so async ingestion tasks keep their trace identity.
"""

from __future__ import annotations

import contextvars
import logging
import os
import sys

import structlog
from structlog.types import EventDict, WrappedLogger

correlation_id_var: contextvars.ContextVar[str | None] = contextvars.ContextVar(
    "correlation_id", default=None
)


def _add_correlation_id(_: WrappedLogger, __: str, event_dict: EventDict) -> EventDict:
    cid = correlation_id_var.get()
    if cid is not None:
        event_dict["correlation_id"] = cid
    return event_dict


def configure_logging(*, level: int = logging.INFO) -> None:
    json_mode = os.environ.get("LOG_FORMAT", "").lower() == "json"

    shared_processors: list[structlog.types.Processor] = [
        structlog.contextvars.merge_contextvars,
        structlog.processors.add_log_level,
        structlog.processors.TimeStamper(fmt="iso", utc=True),
        _add_correlation_id,
    ]

    if json_mode:
        renderer: structlog.types.Processor = structlog.processors.JSONRenderer()
    else:
        renderer = structlog.dev.ConsoleRenderer(colors=sys.stderr.isatty())

    structlog.configure(
        processors=[*shared_processors, renderer],
        wrapper_class=structlog.make_filtering_bound_logger(level),
        context_class=dict,
        logger_factory=structlog.PrintLoggerFactory(),
        cache_logger_on_first_use=True,
    )

    logging.basicConfig(level=level, format="%(message)s", stream=sys.stderr)


def get_logger(name: str | None = None) -> structlog.stdlib.BoundLogger:
    return structlog.get_logger(name)
