"""Toy / Phase-1 batted-ball model.

This subpackage holds the **plumbing-only** binary HR classifier (Phase 1.3)
and any future batted-ball models. The toy model is deliberately
under-engineered — no calibration, no per-park heads, no eval artifact,
no registry entry. Its sole job is to prove the ONNX export + Java load
+ HTTP serve path in Phase 1.4-1.5.

The real batted-ball work (multi-output MLP with 30 per-park heads +
isotonic calibrators + physics retrodiction) lives in Phase 2c.
"""
