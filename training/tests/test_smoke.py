"""Smoke test: package imports and logging configures."""

from bullpen_training import __version__
from bullpen_training.logging_config import configure_logging, get_logger


def test_version_present() -> None:
    assert __version__ == "0.1.0"


def test_logger_configures() -> None:
    configure_logging()
    log = get_logger("smoke")
    log.info("ping", value=1)
