# WSL2 VM stop / "the box crashed" triage

> **Why this exists.** On 2026-07-21 the prod box was reported to "keep crashing".
> The investigation found no crash. What was really happening: (1) GlitchTip's
> celery worker was OOM-looping every ~10s and destabilizing the box, and (2) the
> journal was persistent but uncapped, so ~4 days of a single boot's logs were
> corrupted away before anyone could read them. This runbook captures the method
> so the next occurrence is a 5-minute triage, not a multi-hour dig. It was the
> single artifact whose absence cost the most that day.

## Step 0 - is it actually a crash? (do this FIRST, inside WSL)

```bash
uptime
```

- **Reports days/hours** - the VM never went down. What died was the terminal or
  the Claude Code CLI session; the box kept serving. This is a client/session
  problem, NOT a box problem - stop here and reconnect. Confirm the app is fine:
  `curl -fsS http://localhost:8080/actuator/health/readiness`.
- **Reports minutes** - the VM really did stop. Continue below.

## Step 1 - did the VM stop cleanly, and was it memory? (inside WSL)

```bash
journalctl --list-boots                       # is the previous boot even recorded?
journalctl -b -1 -k --no-pager | grep -iE "out of memory|killed process|oom-kill" | tail -40
journalctl -b -1 --no-pager | tail -120       # the final lines before the stop
```

Read the OOM lines carefully - the `constraint` field is decisive:

- **`constraint=CONSTRAINT_MEMCG`** - a CONTAINER hit ITS OWN cgroup cap. The
  `task=` / cgroup scope names which container. This does NOT kill the VM by
  itself; it points at a misconfigured container (see the GlitchTip case below).
- **A system-wide OOM (no memcg constraint)** - the VM genuinely ran out of
  memory. Check the budget: summed container caps + JVM heaps must fit the
  `.wslconfig` `memory=` pin (see Step 3).
- **`-b -1` tail just stops mid-normal-logging, no systemd "Stopping" sequence** -
  the VM was powered off from OUTSIDE (a `wsl --shutdown`/`--terminate`, Docker
  Desktop, or a host event). Go to Step 2.

If `--list-boots` shows only boot 0 despite persistent storage, a crash-loop
corrupted the journal. Read the raw rotated files directly:
`sudo journalctl --file=/var/log/journal/*/system@*.journal~ --no-pager | tail -150`

## Step 2 - the Windows side (elevated PowerShell on the host)

The WSL utility VM is a Windows guest; its stop is recorded host-side even when
the Linux journal is gone.

```powershell
# Did WINDOWS reboot (taking WSL with it), or only the VM stop?
(Get-CimInstance Win32_OperatingSystem).LastBootUpTime

# Host memory pressure? (empty result = Windows never hit critical pressure)
Get-WinEvent -FilterHashtable @{LogName='System'; ProviderName='Microsoft-Windows-Resource-Exhaustion-Detector'} -MaxEvents 20 -ErrorAction SilentlyContinue | Format-List TimeCreated, Id, Message

# FULL VM stop history (survives journald corruption; back months).
#   PAIRED events at one timestamp (...87E AND ...87F) = full `wsl --shutdown`.
#   A SINGLE event with a unique GUID = one distro VM terminated (--terminate-shaped).
Get-WinEvent -FilterHashtable @{LogName='System'; ProviderName='Microsoft-Windows-Hyper-V-VmSwitch'; Id=69} -MaxEvents 500 -ErrorAction SilentlyContinue |
  Where-Object { $_.Message -match 'WSL' } |
  Select-Object TimeCreated, @{n='Msg';e={$_.Message -replace "`r`n",' '}} | Format-Table -AutoSize

# Did Windows Update / a scheduled task cycle WSL?
Get-WinEvent -FilterHashtable @{LogName='System'; ProviderName='Microsoft-Windows-WindowsUpdateClient'} -MaxEvents 10 -ErrorAction SilentlyContinue | Format-List TimeCreated, Message
Get-ScheduledTask | Where-Object { $_.Actions.Execute -match 'wsl' } | Get-ScheduledTaskInfo | Format-List TaskName, LastRunTime, LastTaskResult
```

Reading it: a stop with an orderly NIC teardown (VmSwitch `234` disconnect ->
`233` delete -> `69` port delete) and no Resource-Exhaustion / WER / Hyper-V-Worker
error is a DELIBERATE stop, not a fault - something asked WSL to stop. A hard
fault instead shows Hyper-V-Worker or WER `vmmem`/`wslservice` crash entries.

## Step 3 - the memory budget (reference)

`.wslconfig` lives on Windows at `%UserProfile%\.wslconfig` and CANNOT be shipped
by git (ADR-0006 - reconstruct it from here). As of 2026-07-21 it pins
`memory=12GB`, `swap=8GB`, `vmIdleTimeout=-1`. The VM pool is ~11.7 GiB.

Summed container caps (ClickHouse 6g + Prometheus 768m + Grafana 512m +
Alertmanager 256m + node-exporter 128m + GlitchTip's four ~2.25g) plus the JVM
heaps (api 1g + worker 512m) already total ~11.4 GiB - before JVM non-heap, the
ONNX native arenas, dockerd, and page cache. The box is memory-tight by design;
dropping the `errortracking` profile reclaims ~2.25 GiB
(`BULLPEN_STACK_PROFILES="monitoring" ...`, see infra/check-stack.sh).

The real 6g ClickHouse cap lives only in the gitignored `infra/.env`; the
committed default is different, so a restore drill can bring CH up heavier -
verify with `docker inspect -f '{{.Name}} {{.HostConfig.Memory}}' $(docker ps -q)`
(a `0` means UNBOUNDED).

## Known cause #1: GlitchTip celery OOM loop (RESOLVED 2026-07-21)

Symptom: load average ~5 while "idle", a multi-GB journal, corrupted journal
rotations every few minutes, and `bullpen-glitchtip-worker` with
`OOMKilled=true` restarting every ~10s. Root cause: Celery's default worker
concurrency scaled with CPU count and forked ~13 children into the worker's 768m
cap. Fixed by `CELERY_WORKER_CONCURRENCY=1` (infra/docker-compose.yml). If it
recurs, confirm the env var reached the container:
`docker exec bullpen-glitchtip-worker printenv CELERY_WORKER_CONCURRENCY`.
Stop the loop immediately with `docker stop bullpen-glitchtip-worker` (an
`unless-stopped` container stays stopped across reboots once stopped).

## Recovery

```bash
sudo journalctl --vacuum-size=500M          # reclaim a bloated journal now
sudo journalctl --verify 2>&1 | tail -20    # check for further corruption
docker compose -f infra/docker-compose.yml ps   # what came back up
```

The app auto-recovers on VM restart (systemd units + `WarmupReadiness`); confirm
with the readiness curl in Step 0. If a genuine host-VM stop is confirmed and
unexplained, write it up under `docs/postmortems/`.
