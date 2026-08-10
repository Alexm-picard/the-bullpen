# training/

Python 3.11+ (uv-managed) model training, evaluation, and ONNX export. The full
feature-table -> train -> export sequence, the runbooks, and the Python<->Java contract live
in the root [README](../README.md) and [docs/runbooks/](../docs/runbooks/) - this file only
carries what is training-specific to running the suite.

## Running the tests

```bash
cd training
OMP_NUM_THREADS=1 uv run python -m pytest          # full suite
uv run python -m pytest tests/leakage -x           # the four CI-required leakage gates
uv run ruff format --check . && uv run ruff check .
uv run python -m pyright
```

Always `uv run python -m pytest` (the bare `pytest` script can resolve a system-framework
install) and always from inside `training/` (running ruff/pyright from the repo root picks
up the wrong config).

## Known Mac-local test traps (CI on Linux is the arbiter)

Both verified pre-existing on clean `main` (2026-07-02); neither reproduces in CI.

1. **Full-suite segfault without `OMP_NUM_THREADS=1`.** torch and lightgbm each bundle
   their own OpenMP on macOS arm64; loading both in one process can crash inside native
   LightGBM. The single-thread pin is the standard workaround and does not change any test
   semantics.
2. **The TOY parity pair fails locally** - Python
   `tests/battedball/test_python_java_parity.py::test_python_onnx_matches_expected_for_every_row`
   and the Java `ToyParityTest`. Root cause: the `_toy` LightGBM miniature TRAINS
   platform-sensitively, so regenerating on macOS produces a different model than the
   Linux-CI-generated committed fixture (`tests/fixtures/parity_toy_001*.json`). Never
   commit a Mac-generated toy fixture; expect exactly these two local failures and let CI
   arbitrate. The allparks/pre/post parity miniatures regenerate byte-identically on both
   platforms (deterministic-export graphs) - only the toy pair is affected. Regenerate the
   non-toy parity artifacts locally with the four generator commands in
   `.github/workflows/backend.yml` (the "Generate ... parity artifacts" steps) if they go
   stale.
