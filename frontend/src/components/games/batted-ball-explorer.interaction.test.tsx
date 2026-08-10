// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { MantineProvider } from "@mantine/core";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeAll, describe, expect, it } from "vitest";

import { SHOWCASE_BATTED_BALL } from "../../data/batted-ball-fixtures";
import { theme } from "../../design/theme";
import { installMantineShims } from "../../test-support/behavioral";

import { BattedBallExplorer } from "./batted-ball-explorer";

/**
 * The per-park rows live behind "Compare across parks", so `renderToStaticMarkup` never reaches
 * them - which is why the first version of this pin was VACUOUS: it asserted the absence of a
 * band that was absent because the whole section was collapsed, not because the fix worked. The
 * mutation (hardcoding a band back) left it green. These tests expand the section first.
 */
beforeAll(installMantineShims);
afterEach(cleanup);

function show(data: typeof SHOWCASE_BATTED_BALL) {
  return render(
    <MantineProvider theme={theme}>
      <BattedBallExplorer data={data} />
    </MantineProvider>,
  );
}

describe("BattedBallExplorer park rows", () => {
  it("prints NO uncertainty band when the model reports none", async () => {
    const live = {
      ...SHOWCASE_BATTED_BALL,
      parks: SHOWCASE_BATTED_BALL.parks.map((p) => ({ ...p, err: null })),
    };
    show(live);
    await userEvent.click(
      screen.getByRole("button", { name: /compare across parks/i }),
    );

    // AllParksResponse carries no per-park uncertainty. A fixed band beside a real model carry is
    // an invented confidence interval, read as the model's own precision.
    expect(document.body.textContent).not.toContain("±");
    expect(document.body.textContent).toMatch(/\d+\s*ft/);
  });

  it("still prints the band for the showcase, which says it is an illustration", async () => {
    show(SHOWCASE_BATTED_BALL);
    await userEvent.click(
      screen.getByRole("button", { name: /compare across parks/i }),
    );
    expect(document.body.textContent).toContain("±");
  });
});
