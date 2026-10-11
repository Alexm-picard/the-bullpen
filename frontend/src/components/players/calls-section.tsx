/**
 * <CallsSection> - "What the models said" on a player file (SPEC-players 6.2, 6.3, 6.6).
 *
 * Left: the settled next-pitch calls (made before the pitch, graded after it) as a running
 * account, newest first, under a REGISTER line that states how many came true as a count. The
 * count is deliberately small and never a percentage or a hero figure (owner decision): it is at
 * most 50 calls of one player, top-1 of five outcomes.
 * Right: where his calls landed by confidence - confidence only, with a note saying what that is
 * NOT, linking to the model-wide graded record on /accuracy.
 *
 * Only settled rows (a winner AND an observed outcome) feed the account: an unsettled call has no
 * truth to agree with yet.
 */
import { VisuallyHidden } from "@mantine/core";
import { Link } from "react-router";

import { usePlayerCalibration, usePlayerPredictions } from "../../api/players";
import {
  calledPhrase,
  happenedPhrase,
  settledCalls,
} from "../../lib/player-file";
import { agateShare } from "../../lib/pitch-type-prior";

import { ConfidenceHistogram } from "./confidence-histogram";

const ACCOUNT_ROWS = 10;
const COUNT = new Intl.NumberFormat("en-US");
const ET_DAY = new Intl.DateTimeFormat("en-US", {
  month: "short",
  day: "numeric",
  timeZone: "America/New_York",
});
const ET_TIME = new Intl.DateTimeFormat("en-US", {
  hour: "numeric",
  minute: "2-digit",
  timeZone: "America/New_York",
});

function Shell({ rows, label }: { rows: number; label: string }) {
  return (
    <div role="status" aria-busy="true">
      <VisuallyHidden>{label}</VisuallyHidden>
      <div aria-hidden="true">
        {Array.from({ length: rows }, (_, i) => (
          <div
            key={i}
            className="ed-shell"
            style={{ height: "1.25rem", margin: "0.75rem 0" }}
          />
        ))}
      </div>
    </div>
  );
}

export function CallsSection({ playerId }: { playerId: number }) {
  const predictions = usePlayerPredictions(playerId);
  const calibration = usePlayerCalibration(playerId, "pitch_outcome_pre");
  const calls = settledCalls(predictions.data ?? []);
  const hits = calls.filter((c) => c.agreed).length;
  const bins = calibration.data ?? [];
  const totalBinned = bins.reduce((s, b) => s + b.n, 0);

  return (
    <section className="pl-part" id="calls" aria-labelledby="pl-calls-title">
      <div className="pl-part__head">
        <p className="ed-slug">What the models said</p>
        <span className="pl-asof">logged calls, graded within 24 hours</span>
      </div>
      <div className="pl-split">
        <div>
          <h2 className="ed-h2" id="pl-calls-title">
            The calls, as they happened
          </h2>
          <p className="ed-sub">
            Next-pitch calls from pitch_outcome_pre, made before each pitch and
            graded after it.
          </p>
          {predictions.isError ? (
            <p className="ed-note">Could not load his calls.</p>
          ) : predictions.isPending ? (
            <Shell rows={6} label="Loading his calls" />
          ) : calls.length === 0 ? (
            <p className="ed-note" data-testid="calls-empty">
              No settled calls for him yet. This fills in as the pitch model
              reads his games and each pitch is graded. Empty by design, not an
              error.
            </p>
          ) : (
            <>
              <p className="pl-register" data-testid="calls-register">
                <strong>
                  {`${hits} of his last ${calls.length} settled calls came true.`}
                </strong>{" "}
                Top-1 of 5 outcomes; a small sample, so read it as a count, not
                a rate.
              </p>
              <ol className="pl-acct" aria-label="Settled calls, newest first">
                {calls.slice(0, ACCOUNT_ROWS).map((c) => {
                  const at = new Date(c.requestAt);
                  return (
                    <li key={c.requestAt}>
                      <time dateTime={c.requestAt}>
                        <span>{ET_DAY.format(at)}</span>
                        <span>{ET_TIME.format(at)}</span>
                      </time>
                      <p>
                        Called <b>{calledPhrase(c.called)}</b>
                        {c.prob == null ? "" : ` at ${agateShare(c.prob)}`}; it
                        was <b>{happenedPhrase(c.happened)}</b>.
                      </p>
                      <span
                        className={
                          c.agreed ? "pl-acct__v pl-acct__v--yes" : "pl-acct__v"
                        }
                      >
                        {c.agreed ? "Yes" : "No"}
                      </span>
                    </li>
                  );
                })}
              </ol>
              {calls.length > ACCOUNT_ROWS ? (
                <p className="ed-prov">
                  {`The newest ${ACCOUNT_ROWS} of ${calls.length} settled calls. Times are ET.`}
                </p>
              ) : (
                <p className="ed-prov">Times are ET.</p>
              )}
            </>
          )}
        </div>
        <aside aria-labelledby="pl-landed-title">
          <h3 className="ed-h3" id="pl-landed-title" style={{ marginTop: 0 }}>
            Where his calls landed
          </h3>
          {calibration.isError ? (
            <p className="ed-note">Could not load where his calls landed.</p>
          ) : calibration.isPending ? (
            <div
              className="ed-shell"
              aria-hidden="true"
              style={{ height: "8.25rem", marginTop: "0.75rem" }}
            />
          ) : bins.length === 0 || totalBinned === 0 ? (
            <p className="ed-note" data-testid="landed-empty">
              No calls logged for him yet.
            </p>
          ) : (
            <>
              <p className="pl-mono-slug">
                {`pitch_outcome_pre, ${COUNT.format(totalBinned)} calls, by confidence`}
              </p>
              <div style={{ marginTop: "0.75rem" }}>
                <ConfidenceHistogram bins={bins} />
              </div>
              <p className="ed-note" data-testid="landed-note">
                How confident the model was, not how often it was right. Grading
                his calls by confidence needs a per-player truth-join we do not
                run yet, so no observed line is drawn. The{" "}
                <Link className="ed-link" to="/accuracy">
                  model-wide record
                </Link>{" "}
                is on Accuracy.
              </p>
            </>
          )}
        </aside>
      </div>
    </section>
  );
}
