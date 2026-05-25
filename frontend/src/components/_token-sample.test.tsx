/**
 * Snapshot of the token-sample visual ground-truth. The card renders one of every
 * primitive (warm-paper surfaces, brick-red accent, three font families, viridis +
 * categorical viz ramps) — so if a token value or a structural choice shifts in
 * tokens.ts or theme.ts, the snapshot diff makes the change loud.
 *
 * Uses renderToStaticMarkup so the test stays node-only — no jsdom dep just for
 * this single snapshot.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { theme } from "../design/theme";
import { colors } from "../design/tokens";

import { TokenSampleCard } from "./_token-sample";

describe("TokenSampleCard", () => {
  it("renders a stable snapshot of every design primitive", () => {
    const html = renderToStaticMarkup(
      <MantineProvider theme={theme}>
        <TokenSampleCard />
      </MantineProvider>,
    );
    expect(html).toMatchSnapshot();
  });

  it("references the brand accent inline (the canonical chromatic note)", () => {
    const html = renderToStaticMarkup(
      <MantineProvider theme={theme}>
        <TokenSampleCard />
      </MantineProvider>,
    );
    expect(html.toLowerCase()).toContain(colors.accent.toLowerCase());
  });
});
