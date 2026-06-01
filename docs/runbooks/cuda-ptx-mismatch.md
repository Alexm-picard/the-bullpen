# Runbook: numba `CUDA_ERROR_UNSUPPORTED_PTX_VERSION` on the desktop

> **Scope:** the GPU-B fused retrodiction kernel
> (`training/.../battedball/physics/_fused.py`) failing at launch on the WSL2
> desktop with `CUDA_ERROR_UNSUPPORTED_PTX_VERSION`. Authored on the MacBook,
> run on the desktop (ADR-0006). The CPU path (`--device cpu`) is unaffected.

## Symptom

Running the retrodiction pipeline with `--device cuda` (or `auto` on a GPU box)
aborts when the kernel first JIT-compiles:

```
numba.cuda.cudadrv.driver.CudaAPIError: [222] Call to cuModuleLoadDataEx
results in CUDA_ERROR_UNSUPPORTED_PTX_VERSION
```

`cuda.is_available()` returns `True` and the kernel builds, but the **first
launch** dies.

## Root cause

A toolkit/driver version skew. On the desktop the **system CUDA toolkit is
13.2** while the **GPU driver is 13.1**. numba compiles device code with the
libNVVM it finds first (the 13.2 system toolkit), which emits a PTX ISA version
the **13.1 driver cannot JIT** — the driver only accepts PTX at or below its own
version. Hence `UNSUPPORTED_PTX_VERSION` at module load, not at build time.

This is not a bug in the kernel — it is purely which libNVVM numba picks up.

## Fix

Make numba use a **CUDA 12.9 libNVVM** instead of the system 13.2 one. CUDA-12.x
PTX is well below the 13.1 driver's ceiling, so it JITs cleanly. Two pieces, both
committed to the repo so the desktop gets them via `git pull` + `uv sync`:

### 1. Pin the NVVM-bearing wheel (already in `pyproject.toml` / `uv.lock`)

```
nvidia-cuda-nvcc-cu12==12.9.86 ; sys_platform == 'linux'
```

The marker is **lowercase `linux`** (matches `sys.platform`; `"Linux"` would
never match). Linux-only because that is the only place the GPU runs; macOS dev
never installs it. (CI is Linux, so it pulls the ~40 MB wheel too — harmless, it
is never loaded without a GPU.)

### 2. Point numba at the wheel's NVVM via a synthetic `CUDA_HOME`

numba 0.65 honours **`CUDA_HOME`** as the NVVM override, so
`scripts/setup_cuda_home.sh` assembles a minimal one at `~/.bullpen-cuda-home`:

| Synthetic path                | Symlinks to                          | Why                                                               |
| ----------------------------- | ------------------------------------ | ----------------------------------------------------------------- |
| `nvvm/lib64/libnvvm.so.4.0.0` | the wheel's `nvvm/lib64/libnvvm.so*` | numba's find-lib regex wants a **version-suffixed** soname        |
| `nvvm/libdevice`              | the wheel's `nvvm/libdevice`         | NVVM needs `libdevice.*.bc` to lower device code                  |
| `nvvm/bin`                    | the wheel's `nvvm/bin`               | NVVM helper binaries (`cicc`)                                     |
| `lib64`                       | `/usr/local/cuda/lib64`              | the runtime libs still come from the system CUDA (driver-matched) |

The script is **idempotent** (re-runnable; `ln -sfn` replaces stale links) and a
**no-op on macOS**. It does not use `set -e`, so sourcing it never mutates your
shell options.

## Procedure (on the desktop)

```bash
cd ~/code/the-bullpen && git pull --ff-only
cd training && uv sync            # installs nvidia-cuda-nvcc-cu12==12.9.86

# Build + export CUDA_HOME into this shell:
source scripts/setup_cuda_home.sh

# Verify numba now agrees with the driver:
uv run python - <<'PY'
from numba import cuda
from bullpen_training.battedball.physics._fused import gpu_available, simulate_classify_batch
import numpy as np
print("cuda.is_available():", cuda.is_available())
print("kernel built:", gpu_available())
# Force a real launch — this is what used to raise UNSUPPORTED_PTX_VERSION.
fa = np.zeros((1, 2), np.float32); fd = np.full((1, 2), 350.0, np.float32)
fh = np.full((1, 2), 8.0, np.float32); fn = np.array([2], np.int32)
ti = np.zeros((1, 12), np.float32); ti[0, 0] = 40.0; ti[0, 2] = 25.0; ti[0, 3] = 1.0; ti[0, 8] = 1.2
print("launch ok, code =", int(simulate_classify_batch(ti, np.zeros(1, np.int32), fa, fd, fh, fn, device="cuda")[0]))
PY
```

If the launch prints a code (0–4) instead of raising, the skew is resolved. Then
run the pipeline as normal with `--device cuda` (or `DEVICE=cuda
bash scripts/run_2c_overnight.sh`). `CUDA_HOME` must be set in the same shell /
env that runs the pipeline, so `source scripts/setup_cuda_home.sh` first (or
export it in the `nohup …` env).

## Notes / alternatives considered

- **Don't "fix" it by upgrading the driver to 13.2.** Driver upgrades on the WSL2
  host are heavier and out-of-band; pinning the NVVM the project uses is
  self-contained, reproducible from the repo, and survives a clean rebuild.
- **Downgrading the system toolkit to 13.1** would also work but is a
  host-global change outside the repo — exactly the kind of untracked desktop
  state ADR-0006's restore drill exists to flush out. The wheel pin keeps the
  fix in version control.
- If the driver is later updated so the system toolkit matches, this synthetic
  `CUDA_HOME` is harmless to keep — but you can stop `source`-ing the script and
  numba will fall back to the system NVVM.
