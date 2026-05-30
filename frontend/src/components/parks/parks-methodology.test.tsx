/**
 * Unit test for <ParksMethodology>.
 *
 * The component is a thin presentation strip; we verify the supplied
 * methodology line renders verbatim and that the n=437,210 token from the
 * canonical line is present.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { PARKS_META } from "../../data/parks-fixtures";
import { theme } from "../../design/theme";

import { ParksMethodology } from "./parks-methodology";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

describe("ParksMethodology", () => {
  it("renders the supplied methodology line verbatim", () => {
    const html = render(<ParksMethodology line={PARKS_META.methodologyLine} />);
    expect(html).toContain(PARKS_META.methodologyLine);
  });

  it("contains the n=437,210 sample-size token", () => {
    const html = render(<ParksMethodology line={PARKS_META.methodologyLine} />);
    expect(html).toContain("n=437,210");
  });
});
