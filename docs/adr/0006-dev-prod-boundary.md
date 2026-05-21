# ADR-0006: Local development on macOS, deployment to self-hosted Linux

- **Status**: Accepted
- **Date**: 2026-05-21
- **Deciders**: alex
- **Related**: `decisions.md` entries [11] [15] [16] [20] [21] [126], `plan.md` Phase 0, `design.md` §8

## Context

The desktop (Windows 11 + WSL2 Ubuntu 24.04 LTS, per decision [15]) is the
production server for The Bullpen: bare-metal systemd services for the
Spring Boot JAR (decisions [16] [25]), Docker for stateful services
(ClickHouse, Prometheus, Grafana), Cloudflare Tunnel exposing
`thebullpen.net` (decision [9]), external monitoring via Better Stack
(decision [17]), scheduled GPU retrains in the 2–6 AM ET window (decision
[19]), and nightly k6 load tests against the live host. It carries a 98 %
uptime target (decision [11]) and the "no deploys during live games" rule
(decision [21]).

It is not a workstation that happens to host code; it is a 24/7 service
with an uptime SLO and an operational story aimed at recruiters reading
the README and the postmortem.

Developing directly on the prod box — editing files in the WSL2 working
copy via Claude Code, SSH, or VS Code Remote — collapses the dev/prod
boundary. It contradicts decision [20]'s "manual deploys via local
script": if the code on the prod box is already the working copy, there
is nothing for `deploy.sh` to deploy. It also creates an undisciplined
write path that would not appear in any operating system you would ship
to teammates, and undermines the hiring-readiness narrative that the
build produces.

The MacBook (macOS) is the natural developer machine: portable, has the
authoring environment already configured, can run the full backend +
frontend + sample-data training locally. The desktop's value is as an
operating target.

## Decision

We treat local development and production as two distinct machines with a
git push as the only authoring boundary between them:

- **All code edits happen on the MacBook** (or in CI). The desktop is a
  deployment target, never an authoring target.
- **Deployment crosses git.** The MacBook pushes to `main` (or a feature
  branch); the desktop's `./deploy.sh` (decision [20]) checks out the
  same SHA and restarts the systemd units.
- **Read-only remote access to the desktop is allowed and encouraged**
  for OBSERVATION: tailing logs, querying ClickHouse, inspecting Grafana,
  poking a flapping service. Tools used for this purpose (SSH, Claude
  Code Remote Control over SSH, Better Stack tail) are read-mostly by
  convention.
- **No `git commit` from the desktop, no editor-driven file mutation in
  `/home/alepic/code/the-bullpen` on the desktop.** The desktop's working
  copy is owned by `deploy.sh` alone.

This applies to all code paths — backend, training, frontend, infra
scripts. It does not apply to operational state that is supposed to live
only on the desktop (systemd unit files in `/etc/systemd/system`,
`/etc/default/bullpen`, ClickHouse data dirs, the sudoers fragment from
`infra/backup/install-sudoers.sh`). Anything that lives only on the
desktop must be reconstructable from the repo plus a documented bootstrap.

## Consequences

**Easier:**

- The deploy script is a real artifact with a real purpose, not a thin
  wrapper around in-place editing. The hiring story ("deploy is a
  scripted, gated action") holds up.
- Reproducibility pressure on the repo is constant: anything the desktop
  needs but the repo doesn't carry gets surfaced as friction the next
  time `deploy.sh` runs on a fresh machine.
- The 98 % uptime target is achievable. A half-saved file or an unstaged
  experiment on the prod box can't break the running service, because no
  one is editing on the prod box.

**Harder:**

- The full ML training pipeline cannot run on the MacBook. No CUDA, no
  full ClickHouse historical dataset (decision [86]'s pybaseball pull is
  ~200 GB by the end of the historical backfill). Mitigation: `make
train-sample` runs locally on a stratified sample for iteration;
  `make train-full` triggers a job on the desktop (over SSH, but as a
  command, not an edit). This is the trigger boundary, not a workflow
  boundary.
- Live debugging requires switching machines. When a service flaps in
  production, the loop is: tail logs from Mac → reproduce locally → fix
  on Mac → push → deploy. No editing-in-place to test a one-line fix.
- Two machines must be kept in sync. The MacBook needs the same Java,
  Python, Node, and Docker versions the desktop runs, or "works on my
  machine" becomes a real failure mode. CI runs against the same matrix
  to catch drift.

**New failure modes:**

- "Untracked desktop state" — config or data that exists only on the
  desktop and never made it back to the repo. Mitigation: the restore
  drill (rule 8, decisions [14] [42]) is the periodic forcing function;
  if a clean WSL2 + `git clone` + `deploy.sh` + restore from B2 backup
  doesn't produce a working system, the gap is whatever's only on the
  desktop.
- "Forgot to push" — a fix made locally that wasn't pushed before
  `deploy.sh` ran on the desktop. Mitigation: `deploy.sh` prints the
  deployed SHA; the deploy-safely skill cross-checks against the most
  recent push.

**Locked into:**

- The desktop has no editor configured for the working copy. Adding one
  ("just to fix this one line, right now") is the slippery slope this
  ADR exists to prevent. A real urgent fix is "open the laptop, fix,
  push, redeploy."

## Alternatives Considered

### Alternative A: Claude Code Remote Control from Mac into the desktop's WSL session

- Use Claude Code's remote control feature to author code in the WSL2
  working copy on the desktop, from the MacBook.
- Rejected: this is dev-on-prod with extra steps. The working copy on
  the desktop becomes the source of truth between commits, the
  MacBook's clone goes stale, and `deploy.sh` becomes ceremonial. The
  hiring story degrades from "I built a real deploy pipeline" to "I
  edit live and bounce the service." Saving 30 seconds per fix is not
  worth the operational story it costs.

### Alternative B: SSH / VS Code Remote into WSL2

- Mount the desktop's WSL2 filesystem over SSH and edit from the
  MacBook's editor.
- Rejected: same boundary collapse as A, plus port-forwarding complexity
  given WSL2's NAT'd networking. Cloudflare Tunnel is the only ingress
  by design (decision [9]); adding an SSH ingress widens the public
  attack surface for no operational benefit.

### Alternative C: Single-machine dev on the desktop, no MacBook involvement

- Use the desktop for both authoring and serving. Skip the MacBook
  entirely.
- Rejected: contradicts decision [20]. Also: the MacBook is the machine
  that's physically with the user during most of the day, on planes, in
  cafes, away from home. Constraining authoring to the desktop would
  hard-limit working hours and require remote-control workflows that
  reintroduce the alternative-A problems.

## Revision History

(none)
