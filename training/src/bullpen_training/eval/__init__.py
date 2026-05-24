"""Evaluation harness + metrics for The Bullpen models.

The rolling-origin CV harness (decision [56]) is the only valid way to
split data for any model trained on the pitch corpus. Random splits
are forbidden — see `leakage_guards.assert_no_random_split`.
"""
