import { describe, expect, it } from "vitest";

import { dailyRange } from "./daily-range";

describe("dailyRange", () => {
  it("prints the min and max of the days with at least 100 graded calls", () => {
    expect(
      dailyRange([
        { date: "2026-10-03", n: 2400, top1: 0.58 },
        { date: "2026-10-04", n: 40, top1: 0.9 }, // thin day: must not stretch the range
        { date: "2026-10-05", n: 2600, top1: 0.602 },
      ]),
    ).toBe("Daily range 58.0% to 60.2%.");
  });

  it("omits the line when fewer than two days qualify", () => {
    expect(
      dailyRange([
        { date: "2026-10-03", n: 2400, top1: 0.58 },
        { date: "2026-10-04", n: 99, top1: 0.6 },
      ]),
    ).toBeNull();
    expect(dailyRange(null)).toBeNull();
    expect(dailyRange([])).toBeNull();
  });
});
