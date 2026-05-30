/**
 * Snapshot + contract test for the scouting-report token sample.
 *
 * The snapshot captures every design primitive so that any rename or value
 * change in tokens.ts makes the diff loud. The inline assertion confirms
 * the scarlet accent hex appears as a literal value — the chromatic anchor
 * of the scouting-report identity.
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

  it("references the scarlet accent inline (the canonical chromatic anchor)", () => {
    const html = renderToStaticMarkup(
      <MantineProvider theme={theme}>
        <TokenSampleCard />
      </MantineProvider>,
    );
    // The scarlet hex must appear as an inline style value somewhere
    // in the rendered output — proving tokens.colors.scarlet is consumed directly.
    expect(html.toLowerCase()).toContain(colors.scarlet.toLowerCase());
  });

  it("references the navy chrome hex inline", () => {
    const html = renderToStaticMarkup(
      <MantineProvider theme={theme}>
        <TokenSampleCard />
      </MantineProvider>,
    );
    expect(html.toLowerCase()).toContain(colors.navy.toLowerCase());
  });
});
