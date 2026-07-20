import { test, expect, type Page, type Route } from "@playwright/test";

/**
 * Live-page e2e (audit #7) - deeper than smoke.spec.ts. Every backend call is mocked at the
 * route layer so the tests are deterministic and need no running API: each page's real `/v1/*`
 * contract is exercised, including the first-class empty states and the live-with-fixture-fallback
 * behaviour of /ops. Covers /players/:id, /games/:id, and /ops.
 */

function trackPageErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  return errors;
}

const json = (route: Route, body: unknown, status = 200) =>
  route.fulfill({
    status,
    contentType: "application/json",
    body: JSON.stringify(body),
  });

// --- /players/:id (B2: recent predictions + calibration, with first-class empty) ----------

const PLAYER_ID = 592450; // Aaron Judge

test("player profile renders recent predictions + calibration when the API has data", async ({
  page,
}) => {
  const errors = trackPageErrors(page);
  // A settled prediction (winnerClass AND observedOutcome non-null) is what feeds the table.
  await page.route(`**/v1/players/${PLAYER_ID}/predictions*`, (route) =>
    json(route, [
      {
        requestAt: "2026-06-13T18:00:00Z",
        modelName: "pitch_outcome_pre",
        modelVersion: "v2",
        role: "shadow",
        winnerClass: "ball",
        winnerProb: 0.62,
        observedOutcome: "ball",
        agreed: true,
      },
    ]),
  );
  await page.route(`**/v1/players/${PLAYER_ID}/calibration*`, (route) =>
    json(route, [
      { binStart: 0.0, binEnd: 0.1, predicted: 0.05, actual: 0.04, n: 120 },
      { binStart: 0.1, binEnd: 0.2, predicted: 0.15, actual: 0.17, n: 90 },
    ]),
  );

  await page.goto(`/players/${PLAYER_ID}`);

  // Target the section heading by role (getByText is case-insensitive substring, so the bare
  // string also matches the "Loading recent predictions…" paragraph).
  await expect(
    page.getByRole("heading", { name: "Recent Predictions" }),
  ).toBeVisible();
  // Wait for the predictions query to resolve (the loading paragraph clears).
  await expect(page.getByText("Loading recent predictions")).toHaveCount(0);
  // Data present => the first-class empty states must NOT render.
  await expect(page.getByText("No prediction history yet")).toHaveCount(0);
  await expect(page.getByText("No calibration data yet")).toHaveCount(0);
  expect(errors, "uncaught errors on populated /players/:id").toEqual([]);
});

test("player profile renders the first-class empty state when the API returns []", async ({
  page,
}) => {
  const errors = trackPageErrors(page);
  await page.route(`**/v1/players/${PLAYER_ID}/predictions*`, (route) =>
    json(route, []),
  );
  await page.route(`**/v1/players/${PLAYER_ID}/calibration*`, (route) =>
    json(route, []),
  );

  await page.goto(`/players/${PLAYER_ID}`);

  // prediction_log is sparse for weeks, so the empty state is the COMMON case and is
  // rendered first-class (NoHistoryNote), never as an error or a blank table.
  await expect(page.getByText("No prediction history yet")).toBeVisible();
  await expect(page.getByText("No calibration data yet")).toBeVisible();
  expect(errors, "uncaught errors on empty /players/:id").toEqual([]);
});

// --- /games/:id (live pitch log, populated) ------------------------------------------------

const GAME_ID = 745804;
const GAME: Record<string, unknown> = {
  gameId: GAME_ID,
  gameDate: "2026-06-13",
  homeTeam: "BOS",
  awayTeam: "BAL",
  homeScore: 2,
  awayScore: 1,
  inning: 6,
  status: "IN_PROGRESS",
  detailedState: "In Progress",
};

function pitch(atBat: number, n: number, desc: string, type: string) {
  return {
    gameId: GAME_ID,
    atBatIndex: atBat,
    pitchNumber: n,
    cursor: atBat * 100 + n,
    ingestedAt: "2026-06-13T18:00:00Z",
    pitcherId: 1,
    batterId: 2,
    description: desc,
    pitchType: type,
    releaseSpeedMph: 94.1,
    plateXIn: 0.2,
    plateZIn: 2.4,
    balls: 1,
    strikes: 2,
    outs: 1,
    inning: 6,
    homeScore: 2,
    awayScore: 1,
    // A5 pre-pitch context (V028): mirrors the LivePitchRow DTO shape.
    pitcherThrows: "R",
    batterStand: "L",
    baseState: 0,
    parkId: "TOR",
    scoreDiff: 0,
    predictedClasses: {
      ball: 0.2,
      called_strike: 0.2,
      swinging_strike: 0.2,
      foul: 0.2,
      in_play: 0.2,
    },
    predictedWinner: "ball",
  };
}

test("live game page renders the pitch log when the feed has pitches", async ({
  page,
}) => {
  const errors = trackPageErrors(page);
  await page.route(`**/v1/games/${GAME_ID}`, (route) => json(route, GAME));
  await page.route(`**/v1/games/${GAME_ID}/pitches*`, (route) =>
    json(route, [
      pitch(1, 1, "ball", "FF"),
      pitch(1, 2, "swinging_strike", "SL"),
    ]),
  );

  await page.goto(`/games/${GAME_ID}`);

  await expect(page.locator("h1").first()).toBeVisible();
  // The slug-era guard must not fire on a numeric id.
  await expect(page.getByText("Invalid game id")).toHaveCount(0);
  // The live pitch-log section + the pitch-count stat render against real rows.
  await expect(
    page.locator('[aria-labelledby="game-pitch-log-label"]'),
  ).toBeVisible();
  await expect(
    page.getByText("Pitches", { exact: false }).first(),
  ).toBeVisible();
  expect(errors, "uncaught errors on populated /games/:id").toEqual([]);
});

test("live game page renders the first-class empty pitch-log state when the feed has no pitches", async ({
  page,
}) => {
  const errors = trackPageErrors(page);
  await page.route(`**/v1/games/${GAME_ID}`, (route) => json(route, GAME));
  // An in-progress game whose pitch feed is still empty - the common pre-first-pitch case.
  await page.route(`**/v1/games/${GAME_ID}/pitches*`, (route) =>
    json(route, []),
  );

  await page.goto(`/games/${GAME_ID}`);

  await expect(page.locator("h1").first()).toBeVisible();
  // The slug-era guard must not fire on a numeric id.
  await expect(page.getByText("Invalid game id")).toHaveCount(0);
  // The pitch-log section still renders...
  await expect(
    page.locator('[aria-labelledby="game-pitch-log-label"]'),
  ).toBeVisible();
  // ...with the LivePitchBoard's first-class waiting state, not a blank table or an error.
  await expect(page.getByText("Waiting for the first pitch")).toBeVisible();
  expect(errors, "uncaught errors on empty /games/:id").toEqual([]);
});

// --- /ops (live registry x routing x latency, with fixture fallback) -----------------------

/** Mock every /v1/ops/* read; callers override `registry` to drive the live-vs-fallback path. */
async function mockOps(page: Page, registry: unknown[] = []) {
  await page.route("**/v1/ops/registry/all", (route) => json(route, registry));
  await page.route("**/v1/ops/routing", (route) => json(route, []));
  await page.route("**/v1/ops/latency*", (route) => json(route, []));
  await page.route("**/v1/ops/drift*", (route) => json(route, []));
  await page.route("**/v1/ops/retrain*", (route) => json(route, []));
  // /v1/ops/events is a page object ({rows, page, size, hasNext}), not a bare list - an empty page
  // is a legitimate live "no events yet" state (the ops-log reads data.rows).
  await page.route("**/v1/ops/events*", (route) =>
    json(route, { rows: [], page: 0, size: 20, hasNext: false }),
  );
  await page.route("**/v1/ops/calibration-summary", (route) => json(route, {}));
  // model-names list (used to fan out drift queries) - keep it empty so no extra calls.
  await page.route("**/v1/ops/registry", (route) => json(route, []));
}

test("ops dashboard renders with the fixture fallback when /v1/ops/* are empty", async ({
  page,
}) => {
  const errors = trackPageErrors(page);
  await mockOps(page, []); // empty registry => liveFleet null => fixture fleet

  await page.goto("/ops");

  await expect(page.locator("h1").first()).toBeVisible();
  // The fixture fleet renders (a known fixture model), proving graceful offline fallback.
  await expect(
    page.getByText("lr_baseline", { exact: false }).first(),
  ).toBeVisible();
  expect(errors, "uncaught errors on /ops (fallback)").toEqual([]);
});

test("ops dashboard renders LIVE fleet rows when the registry endpoint returns data", async ({
  page,
}) => {
  const errors = trackPageErrors(page);
  const liveModel = {
    id: 99,
    modelName: "pitch_outcome_post",
    version: "v9",
    artifactPath: "snapshots/pitch_outcome_post/v9",
    metadataPath: "snapshots/pitch_outcome_post/v9/metadata.json",
    trainingDataHash: "abc",
    trainingDataWindow: "2015-2025",
    featureSchemaHash: "def",
    evalMetrics: '{"brier":0.1025}',
    trainedAt: "2026-06-12T00:00:00Z",
    promotedAt: null,
    stage: "SHADOW",
    createdBy: "alex",
    notes: null,
    createdAt: "2026-06-12T00:00:00Z",
    updatedAt: "2026-06-12T00:00:00Z",
  };
  await mockOps(page, [liveModel]);

  await page.goto("/ops");

  // "pitch_outcome_post v9" is NOT in any fixture, so seeing it proves live registry data flowed
  // through toFleetRows into the Model Fleet table.
  await expect(page.getByText(/pitch_outcome_post\s+v9/).first()).toBeVisible();
  expect(errors, "uncaught errors on /ops (live)").toEqual([]);
});
