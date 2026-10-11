/**
 * useBallCommit - the /parks what-if ball as two states (SPEC-parks section 6.1/6.2).
 *
 *   draft      what the controls show RIGHT NOW. Updated on every slider move or key step. Drives
 *              the field drawing and the readouts. Never sent.
 *   committed  the ball the all-parks request is keyed on. Changes only when a gesture ENDS.
 *
 * Why: POST /v1/predict/batted-ball/all-parks logs EVERY request to prediction_log (the drift
 * baselines), so the page sends one request per SETTLED ball and never logs the intermediate
 * balls a drag passes through (owner decision, 2026-10-11).
 *
 * Commit timing, by input source (Mantine Slider fires onChangeEnd once at the end of a pointer
 * scrub, but once per keydown, auto-repeat included):
 *   - pointer release / track click: commit immediately, no debounce (the hand is done);
 *   - keyboard steps (Arrows, Home, End): coalesced, committed KEY_COALESCE_MS after the last
 *     step, so a burst or a held key sends one request;
 *   - bat side: immediate (a discrete choice, not a gesture).
 * A pointer commit or a bat-side commit cancels any pending keyboard commit (it carries the whole
 * current draft, so nothing is lost). A commit equal to the current committed ball is a no-op.
 */
import { useCallback, useEffect, useRef, useState } from "react";

import { sameBall, type Ball } from "../../lib/park-ball";

export const KEY_COALESCE_MS = 300;

export type BallField = "launchSpeedMph" | "launchAngleDeg" | "sprayAngleDeg";
export type InputSource = "pointer" | "key";

export function useBallCommit(initial: Ball) {
  const [draft, setDraft] = useState<Ball>(initial);
  const [committed, setCommitted] = useState<Ball>(initial);
  const [source, setSource] = useState<InputSource>("pointer");
  // The latest draft, readable from the gesture-end callbacks without re-creating them per move.
  const draftRef = useRef<Ball>(initial);
  const timer = useRef<number | null>(null);

  const cancelPending = useCallback(() => {
    if (timer.current != null) {
      window.clearTimeout(timer.current);
      timer.current = null;
    }
  }, []);

  useEffect(() => cancelPending, [cancelPending]);

  const commit = useCallback((ball: Ball) => {
    setCommitted((prev) => (sameBall(prev, ball) ? prev : ball));
  }, []);

  const writeDraft = useCallback((next: Ball) => {
    draftRef.current = next;
    setDraft(next);
  }, []);

  /** Slider onChange: the hand moved (pointer) or a key stepped. Draft only. */
  const move = useCallback(
    (field: BallField, value: number) => {
      writeDraft({ ...draftRef.current, [field]: value });
    },
    [writeDraft],
  );

  /** Slider onChangeEnd: the gesture ended. Commit by source. */
  const end = useCallback(
    (field: BallField, value: number, from: InputSource) => {
      const next = { ...draftRef.current, [field]: value };
      writeDraft(next);
      setSource(from);
      if (from === "pointer") {
        cancelPending();
        commit(next);
        return;
      }
      cancelPending();
      timer.current = window.setTimeout(() => {
        timer.current = null;
        commit(draftRef.current);
      }, KEY_COALESCE_MS);
    },
    [cancelPending, commit, writeDraft],
  );

  /** Bat side: a discrete choice, committed at once with the rest of the current draft. */
  const setStand = useCallback(
    (stand: Ball["stand"]) => {
      const next = { ...draftRef.current, stand };
      writeDraft(next);
      cancelPending();
      commit(next);
    },
    [cancelPending, commit, writeDraft],
  );

  /** Pointer-down on a slider: the next draft-only moves are a drag, so the copy says "release". */
  const begin = useCallback((from: InputSource) => setSource(from), []);

  return {
    draft,
    committed,
    /** True while the controls show a ball that has not been sent yet. */
    pending: !sameBall(draft, committed),
    source,
    move,
    end,
    begin,
    setStand,
  };
}
