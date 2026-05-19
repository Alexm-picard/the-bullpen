---
description: Run the restore drill or reboot drill with full evidence capture
argument-hint: restore | reboot
---

Invoke the `drill-runner` agent for The Bullpen. Drill to run:

$ARGUMENTS

Walk through the drill step by step. Wait for my confirmation between steps. Capture command output as evidence. At the end, write a dated post-drill report to `docs/drills/{date}_{drill_name}.md`. If any step fails, STOP and write a partial report — don't try to fix in flight.
