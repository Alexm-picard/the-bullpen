---
description: Add a new CI job or workflow following The Bullpen's conventions
argument-hint: <one-line description of what the new CI job should check>
---

Invoke the `ci-add` skill for The Bullpen. New CI coverage:

$ARGUMENTS

Follow the skill's procedure: clarify trigger/required/services with me, pick the right file (extend an existing workflow or add a new one), write the job with conventions (path filters, conditional file-presence guards, language-specific caching, services declared in-job), and tell me what branch-protection update is needed if the job is REQUIRED to merge.
