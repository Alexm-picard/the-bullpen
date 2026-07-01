import { test, expect, type Page } from "@playwright/test";

/**
 * Records uncaught page errors so a test can assert the page didn't throw — the cheapest
 * "did this route crash" signal beyond a status check.
 */
function trackPageErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  return errors;
}

test("home renders the brand and a masthead heading", async ({ page }) => {
  const errors = trackPageErrors(page);
  await page.goto("/");
  await expect(page.getByText("the bullpen").first()).toBeVisible();
  await expect(page.locator("h1").first()).toBeVisible();
  expect(errors, "uncaught errors on /").toEqual([]);
});

test("fixture pages render via header nav without crashing", async ({
  page,
}) => {
  const errors = trackPageErrors(page);
  await page.goto("/");
  const header = page.locator("header");
  for (const link of ["parks", "ops", "about", "games"]) {
    await header.getByRole("link", { name: link, exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/${link}$`));
    await expect(page.locator("h1").first()).toBeVisible();
  }
  expect(errors, "uncaught errors during nav").toEqual([]);
});

test("an unknown URL renders the 404 page, not a blank shell (S6)", async ({
  page,
}) => {
  const errors = trackPageErrors(page);
  await page.goto("/this-route-does-not-exist");
  await expect(page.getByText("404")).toBeVisible();
  await expect(page.getByText("No play at this base.")).toBeVisible();
  await expect(page.getByRole("link", { name: /back to home/i })).toBeVisible();
  // The nav chrome still frames the page (the 404 route lives inside the Layout).
  await expect(page.locator("header")).toBeVisible();
  expect(errors, "uncaught errors on a 404 URL").toEqual([]);
});

test("player lookup renders its search input", async ({ page }) => {
  const errors = trackPageErrors(page);
  await page.goto("/players");
  // Mantine's Autocomplete renders a role="combobox" input — target the element directly.
  await expect(page.locator("input").first()).toBeVisible();
  expect(errors, "uncaught errors on /players").toEqual([]);
});

// --- /games live slate (FE-H1) — network mocked at the route layer ---------

const SLATE_GAME = {
  gameId: 745804,
  gameDate: "2026-06-11",
  homeTeam: "BOS",
  awayTeam: "BAL",
  homeScore: 2,
  awayScore: 1,
  inning: 6,
  status: "IN_PROGRESS",
  detailedState: "In Progress",
};

test("games slate degrades to the showcase slate when the live API is empty", async ({
  page,
}) => {
  const errors = trackPageErrors(page);
  // Both live sources settled-empty -> the page falls back to the committed showcase slate
  // (decision [160]), so a card still renders rather than a blank/empty view.
  await page.route("**/v1/games/today", (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: "[]" }),
  );
  await page.route("**/v1/matchups/today", (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: "[]" }),
  );
  await page.goto("/games");
  await expect(
    page.getByRole("link", { name: /open game for/i }).first(),
  ).toBeVisible();
  expect(errors, "uncaught errors on empty /games").toEqual([]);
});

test("games slate renders a row and its numeric link opens the live game page", async ({
  page,
}) => {
  const errors = trackPageErrors(page);
  await page.route("**/v1/games/today", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([SLATE_GAME]),
    }),
  );
  await page.route("**/v1/matchups/today", (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: "[]" }),
  );
  // The per-game page's own queries are stubbed too, so the click asserts
  // routing (numeric id accepted), not backend availability.
  await page.route("**/v1/games/745804", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(SLATE_GAME),
    }),
  );
  await page.route("**/v1/games/745804/pitches*", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: "[]",
    }),
  );

  await page.goto("/games");
  await expect(page.getByText("BAL")).toBeVisible();
  const open = page.getByRole("link", {
    name: /open game for BAL at BOS/i,
  });
  await expect(open).toHaveAttribute("href", "/games/745804");

  await open.click();
  await expect(page).toHaveURL(/\/games\/745804$/);
  // The numeric id parses — the slug-era "Invalid game id" must not render.
  await expect(page.getByText("Invalid game id")).toHaveCount(0);
  await expect(page.locator("h1").first()).toBeVisible();
  expect(errors, "uncaught errors navigating the live slate").toEqual([]);
});
