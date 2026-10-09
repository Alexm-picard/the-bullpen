// @vitest-environment jsdom
/**
 * <TickerStrip> behaviour: the crawl never relabels mid-loop, and a real control
 * (not hover alone) pauses it (WCAG 2.2.2).
 */
import "@testing-library/jest-dom/vitest";

import { MantineProvider } from "@mantine/core";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeAll, describe, expect, it } from "vitest";

import { theme } from "../../design/theme";
import { installMantineShims } from "../../test-support/behavioral";

import { TickerStrip, type TickerItem } from "./ticker-strip";

beforeAll(installMantineShims);
afterEach(cleanup);

function show(items: TickerItem[]) {
  return render(
    <MantineProvider theme={theme}>
      <TickerStrip items={items} />
    </MantineProvider>,
  );
}

function rerenderWith(
  rerender: ReturnType<typeof render>["rerender"],
  items: TickerItem[],
) {
  rerender(
    <MantineProvider theme={theme}>
      <TickerStrip items={items} />
    </MantineProvider>,
  );
}

/** jsdom has no AnimationEvent, so React listens under a vendor-prefixed name; fire whichever
 * name it registered (only one of the two reaches the handler). */
function loopBoundary(el: Element) {
  for (const type of ["animationiteration", "webkitAnimationIteration"]) {
    fireEvent(el, new Event(type, { bubbles: true }));
  }
}

describe("TickerStrip", () => {
  it("holds the rendered run until the loop boundary, then swaps it", () => {
    const { container, rerender } = show([{ key: 1, text: "FF 94.8 → ball" }]);
    const track = container.querySelector(".broadcast-ticker__track")!;
    rerenderWith(rerender, [
      { key: 2, text: "SL 86.1 → foul" },
      { key: 1, text: "FF 94.8 → ball" },
    ]);
    // Mid-crawl: the new pitch waits; nothing on screen changes under the reader.
    expect(track.textContent).not.toContain("SL 86.1");
    loopBoundary(track);
    expect(track.textContent).toContain("SL 86.1");
  });

  it("adopts the first items immediately when nothing is crawling yet", () => {
    const { container, rerender } = show([]);
    expect(container.querySelector(".broadcast-ticker")).toBeNull();
    rerenderWith(rerender, ["FF 94.8 → ball"]);
    expect(
      container.querySelector(".broadcast-ticker__track")!.textContent,
    ).toContain("FF 94.8");
  });

  it("pauses and resumes from a visible control outside the hidden crawl", () => {
    const { container } = show(["FF 94.8 → ball"]);
    const strip = container.querySelector(".broadcast-ticker")!;
    expect(strip).toHaveAttribute("aria-hidden", "true");
    expect(strip).toHaveAttribute("data-paused", "false");
    const pause = screen.getByRole("button", {
      name: /pause the pitch ticker/i,
    });
    expect(strip.contains(pause)).toBe(false);
    fireEvent.click(pause);
    expect(strip).toHaveAttribute("data-paused", "true");
    expect(
      screen.getByRole("button", { name: /play the pitch ticker/i }),
    ).toHaveTextContent("Play");
  });
});
