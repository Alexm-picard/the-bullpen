// @vitest-environment jsdom
/**
 * <ModelStandouts> - the /players showcase ranking ([195]). Default board, the metric toggle,
 * live-file links, and the honesty caption (a hand-written board; a leaders endpoint would fill it).
 */
import "@testing-library/jest-dom/vitest";

import { cleanup, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeAll, describe, expect, it } from "vitest";

import { MODEL_STANDOUTS } from "../../data/players-landing-fixtures";
import {
  installMantineShims,
  renderWithProviders,
} from "../../test-support/behavioral";

import { ModelStandouts } from "./model-standouts";

beforeAll(installMantineShims);
afterEach(cleanup);

describe("ModelStandouts", () => {
  it("renders the default xwOBA board with linked players and the showcase caption", () => {
    renderWithProviders(<ModelStandouts />);
    expect(screen.getByRole("link", { name: "Aaron Judge" })).toHaveAttribute(
      "href",
      "/players/592450",
    );
    expect(screen.getByText(".451")).toBeInTheDocument();
    expect(screen.getByText("+.118")).toBeInTheDocument();
    expect(
      screen.getByText(/Showcase ranking, illustrative/),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "xwOBA" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
  });

  it("switches to the xFIP board", async () => {
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<ModelStandouts />);
    await user.click(screen.getByRole("button", { name: "xFIP" }));
    const top = MODEL_STANDOUTS.xfip.rows[0];
    expect(top?.name).toBe("Paul Skenes");
    expect(
      screen.getByRole("link", { name: "Paul Skenes" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("link", { name: "Aaron Judge" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("columnheader", { name: "xFIP" }),
    ).toBeInTheDocument();
  });
});
