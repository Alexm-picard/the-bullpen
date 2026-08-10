import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { theme } from "../../design/theme";

import { BattedBallsView } from "./batted-balls-view";

function render(node: React.ReactElement): string {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return renderToStaticMarkup(
    <QueryClientProvider client={client}>
      <MantineProvider theme={theme}>{node}</MantineProvider>
    </QueryClientProvider>,
  );
}

describe("BattedBallsView", () => {
  it("renders the hit-type, HRs-only, and date-range filters", () => {
    // Static render leaves the query in its loading state; the filter controls render regardless.
    const html = render(<BattedBallsView playerId={592450} />);
    expect(html).toContain("Hit type");
    expect(html).toContain("HRs only");
    expect(html).toContain("From");
    expect(html).toContain("To");
  });
});
