#!/usr/bin/env bash
# GPU thermal textfile collector (M1 task 7).
#
# The box's shutdown risk under load is the GPU during 2-6 AM retrains (CPU temperature is
# unreadable under WSL2), so this is the thermal signal the GpuTempHigh/GpuTempCritical
# Prometheus rules alert on. A systemd timer fires this every 30s; it writes one gauge
# via the node_exporter textfile collector, same atomic tmp+mv pattern as
# infra/backup/clickhouse-snapshot.sh.
#
# Degrades loud-but-harmless: no nvidia-smi (non-GPU host, driver hiccup) exits 0 with a
# note so the timer does not accumulate failed units; an unparseable reading exits 1 so
# `systemctl status` shows red instead of silently publishing garbage.

set -euo pipefail

NODE_TEXTFILE_DIR="${NODE_TEXTFILE_DIR:-/var/lib/node_exporter}"

# WSL2 exposes nvidia-smi under /usr/lib/wsl/lib; cover both it and the normal PATH.
export PATH="${PATH}:/usr/lib/wsl/lib"

if ! command -v nvidia-smi >/dev/null 2>&1; then
  echo "gpu-temp: nvidia-smi not found; skipping (non-GPU host?)" >&2
  exit 0
fi

TEMP="$(nvidia-smi --query-gpu=temperature.gpu --format=csv,noheader,nounits 2>/dev/null | head -1 | tr -d '[:space:]')"
if [[ ! "$TEMP" =~ ^[0-9]+$ ]]; then
  echo "gpu-temp: unparseable temperature reading: '${TEMP}'" >&2
  exit 1
fi

mkdir -p "$NODE_TEXTFILE_DIR"
TEXTFILE="${NODE_TEXTFILE_DIR}/gpu_temp.prom"
TMP_TEXTFILE="${TEXTFILE}.$$"
{
  echo "# HELP nvidia_gpu_temperature_celsius GPU core temperature from nvidia-smi."
  echo "# TYPE nvidia_gpu_temperature_celsius gauge"
  echo "nvidia_gpu_temperature_celsius ${TEMP}"
} > "$TMP_TEXTFILE" && mv -f "$TMP_TEXTFILE" "$TEXTFILE"
