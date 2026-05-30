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

test("player lookup renders its search input", async ({ page }) => {
  const errors = trackPageErrors(page);
  await page.goto("/players");
  // Mantine's Autocomplete renders a role="combobox" input — target the element directly.
  await expect(page.locator("input").first()).toBeVisible();
  expect(errors, "uncaught errors on /players").toEqual([]);
});
