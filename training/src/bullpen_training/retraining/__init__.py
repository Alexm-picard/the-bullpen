"""Python retrain worker (leaf 3d.3).

Single entry point ``python -m bullpen_training.retraining.run``:
1. Claims the next queued trigger via Spring's ``POST /v1/admin/retrain/claim``.
2. Dispatches on ``model_name`` to the matching training pipeline.
3. Calls Spring's registry ``POST /v1/admin/registry/{model_name}/register`` on success.
4. Reports back via Spring's ``POST /v1/admin/retrain/{trigger_id}/complete``.

Propagates ``trigger_id`` into all structured logs + the produced ``metadata.json`` so
post-hoc correlation ("this v17 came from drift trigger of 2026-06-15") is one ``trigger_id``
lookup. Per rule 6 / decision [44]: a successful retrain produces a CANDIDATE-stage row;
promotion stays human-gated via 3a.4's promote endpoint.
"""
