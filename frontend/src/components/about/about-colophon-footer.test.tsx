/**
 * Unit tests for <AboutColophonFooter>.
 *
 * Covers: COLOPHON identity label appears, build SHA + date appear, the
 * repo placeholder appears as plain text (no <a> element), and the navy
 * chrome background is in effect.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { theme } from "../../design/theme";
import { colors } from "../../design/tokens";

import { AboutColophonFooter } from "./about-colophon-footer";

const DEFAULT_PROPS = {
  buildSha: "b1b62ec",
  buildDate: "2026.05.30",
  repoPlaceholder: "github.com/<placeholder>/thebullpen",
};

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

describe("AboutColophonFooter", () => {
  it("renders the COLOPHON identity label", () => {
    const html = render(<AboutColophonFooter {...DEFAULT_PROPS} />);
    expect(html).toContain("COLOPHON");
  });

  it("renders the build SHA and date", () => {
    const html = render(<AboutColophonFooter {...DEFAULT_PROPS} />);
    expect(html).toContain("b1b62ec");
    expect(html).toContain("2026.05.30");
  });

  it("renders the github.com repo placeholder", () => {
    const html = render(<AboutColophonFooter {...DEFAULT_PROPS} />);
    expect(html).toContain("github.com");
    expect(html).toContain("thebullpen");
  });

  it("renders the repo placeholder as plain text (no anchor element)", () => {
    const html = render(<AboutColophonFooter {...DEFAULT_PROPS} />);
    expect(html).not.toContain("<a ");
  });

  it("uses the navy chrome background", () => {
    const html = render(<AboutColophonFooter {...DEFAULT_PROPS} />);
    expect(html.toLowerCase()).toContain(colors.navy.toLowerCase());
  });
});
