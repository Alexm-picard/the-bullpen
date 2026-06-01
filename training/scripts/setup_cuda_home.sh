#!/usr/bin/env bash
# setup_cuda_home.sh — assemble a synthetic CUDA_HOME so numba 0.65 uses the
# pip-installed CUDA 12.9 NVVM instead of the system CUDA toolkit.
#
# Why this exists
# ---------------
# On the WSL2 desktop the system CUDA toolkit (13.2) drifted ahead of the GPU
# driver (13.1). numba's libNVVM then emits PTX the 13.1 driver cannot JIT, so
# the GPU-B retrodiction kernel dies with CUDA_ERROR_UNSUPPORTED_PTX_VERSION.
# Pinning numba's NVVM to the nvidia-cuda-nvcc-cu12==12.9.86 wheel makes it emit
# CUDA-12.x PTX, which the 13.1 driver accepts. numba 0.65 only honours
# CUDA_HOME as the NVVM override, so we build a minimal CUDA_HOME that points at
# the wheel's NVVM (libnvvm + libdevice + bin) and the system runtime libs.
# Full background: docs/runbooks/cuda-ptx-mismatch.md.
#
# Usage (from the training/ directory, on the desktop):
#   source scripts/setup_cuda_home.sh   # builds the dir AND exports CUDA_HOME
# or run it and copy the printed export line into your shell / the run env.
#
# Idempotent and Linux-only (a no-op on macOS). Intentionally does NOT use
# `set -e`, so sourcing it never changes the calling shell's options.

bullpen_setup_cuda_home() {
    local cuda_home="${BULLPEN_CUDA_HOME:-$HOME/.bullpen-cuda-home}"
    local sys_lib64="${SYSTEM_CUDA_LIB64:-/usr/local/cuda/lib64}"

    if [ "$(uname -s)" != "Linux" ]; then
        echo "setup_cuda_home: not Linux ($(uname -s)) — nothing to do." >&2
        return 0
    fi

    # Locate the nvidia-cuda-nvcc-cu12 wheel's nvvm dir inside the uv venv.
    local nvcc_dir nvvm_src src_libnvvm
    nvcc_dir="$(uv run python -c 'import nvidia.cuda_nvcc as m; print(list(m.__path__)[0])' 2>/dev/null)" || {
        echo "setup_cuda_home: cannot import nvidia.cuda_nvcc — run 'uv sync' first." >&2
        return 1
    }
    nvvm_src="$nvcc_dir/nvvm"
    if [ ! -d "$nvvm_src" ]; then
        echo "setup_cuda_home: $nvvm_src not found (unexpected wheel layout)." >&2
        return 1
    fi

    # numba's find-lib regex wants a version-suffixed soname; symlink whatever
    # libnvvm.so* the wheel ships under the canonical libnvvm.so.4.0.0 name.
    src_libnvvm="$(find "$nvvm_src/lib64" "$nvvm_src/lib" -maxdepth 1 -name 'libnvvm.so*' 2>/dev/null | head -n1)"
    if [ -z "$src_libnvvm" ]; then
        echo "setup_cuda_home: no libnvvm.so* under $nvvm_src/{lib64,lib}." >&2
        return 1
    fi

    if [ ! -d "$sys_lib64" ]; then
        echo "setup_cuda_home: warning — $sys_lib64 missing; set SYSTEM_CUDA_LIB64 to the system CUDA lib64." >&2
    fi

    # Assemble the synthetic CUDA_HOME. `ln -sfn` is idempotent and replaces
    # stale links in place, so re-running is safe.
    mkdir -p "$cuda_home/nvvm/lib64"
    ln -sfn "$src_libnvvm" "$cuda_home/nvvm/lib64/libnvvm.so.4.0.0"
    ln -sfn "$nvvm_src/libdevice" "$cuda_home/nvvm/libdevice"
    ln -sfn "$nvvm_src/bin" "$cuda_home/nvvm/bin"
    ln -sfn "$sys_lib64" "$cuda_home/lib64"

    export CUDA_HOME="$cuda_home"
    echo "setup_cuda_home: CUDA_HOME=$cuda_home"
    echo "  nvvm/lib64/libnvvm.so.4.0.0 -> $src_libnvvm"
    echo "  nvvm/libdevice              -> $nvvm_src/libdevice"
    echo "  nvvm/bin                    -> $nvvm_src/bin"
    echo "  lib64                       -> $sys_lib64"
    return 0
}

bullpen_setup_cuda_home
_bullpen_cuda_rc=$?

# When executed (not sourced) the export above dies with the process — remind
# the caller how to make CUDA_HOME stick. Detection works in bash; in zsh the
# export still happened when sourced, the reminder is just harmless extra text.
if [ "$_bullpen_cuda_rc" -eq 0 ] && [ "${BASH_SOURCE:-$0}" = "$0" ]; then
    echo
    echo "Not sourced — CUDA_HOME won't persist. Either source it:"
    echo "  source scripts/setup_cuda_home.sh"
    echo "or export it before the pipeline:"
    echo "  export CUDA_HOME=${BULLPEN_CUDA_HOME:-$HOME/.bullpen-cuda-home}"
fi
