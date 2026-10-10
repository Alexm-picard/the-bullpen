import { describe, expect, it } from "vitest";

import type { Calibration } from "../api/rolling-accuracy";

import {
  calendarWindowStart,
  calibrationMeta,
  calibrationTitle,
  formatIsoDay,
} from "./calibration-window";

function cal(over: Partial<Calibration>): Calibration {
  return {
    definition: "top_label",
    binWidth: 0.1,
    minReadableN: 30,
    windowDays: 30,
    windowKind: "calendar",
    truthFrom: "2026-09-09",
    truthThrough: "2026-10-08",
    gameDays: 30,
    n: 60800,
    bins: [],
    ...over,
  };
}

// 2026-10-09 23:30 UTC = 7:30 PM ET on Oct 9 -> calendar window [Sep 9, Oct 8].
const GENERATED = "2026-10-09T23:30:00Z";

describe("calendarWindowStart", () => {
  it("is today ET minus the window, computed in ET (not UTC)", () => {
    expect(calendarWindowStart(GENERATED, 30)).toBe("2026-09-09");
    // 02:00 UTC on Oct 10 is still Oct 9 in ET.
    expect(calendarWindowStart("2026-10-10T02:00:00Z", 30)).toBe("2026-09-09");
  });

  it("is null for a missing or unparseable stamp", () => {
    expect(calendarWindowStart(null, 30)).toBeNull();
    expect(calendarWindowStart("not a date", 30)).toBeNull();
  });
});

describe("calibrationTitle / calibrationMeta", () => {
  it("full calendar coverage: last N days, through the real truthThrough", () => {
    const c = cal({});
    expect(calibrationTitle(c)).toBe("Calibration, last 30 days");
    expect(calibrationMeta(c, GENERATED)).toBe("60,800 graded, through Oct 8");
  });

  it("never assumes yesterday: an overnight two-days-back truthThrough prints as itself", () => {
    expect(
      calibrationMeta(cal({ truthThrough: "2026-10-07" }), GENERATED),
    ).toBe("60,800 graded, through Oct 7");
  });

  it("partial coverage (truth starts after the window start) prints the dates", () => {
    expect(
      calibrationMeta(cal({ truthFrom: "2026-09-26", n: 24310 }), GENERATED),
    ).toBe("24,310 graded, Sep 26 to Oct 8");
  });

  it("last days of play is its own claim, labelled with the days and dates", () => {
    const c = cal({
      windowKind: "last_days_of_play",
      truthFrom: "2026-09-12",
      truthThrough: "2026-11-01",
      gameDays: 30,
    });
    expect(calibrationTitle(c)).toBe("Calibration, the last 30 days of play");
    expect(calibrationMeta(c, "2027-01-15T15:00:00Z")).toBe(
      "60,800 graded, Sep 12 to Nov 1",
    );
    expect(
      calibrationTitle(cal({ windowKind: "last_days_of_play", gameDays: 12 })),
    ).toBe("Calibration, the last 12 days of play");
  });

  it("an empty window has no meta line (no fabricated dates)", () => {
    expect(
      calibrationMeta(
        cal({ n: 0, truthFrom: null, truthThrough: null }),
        GENERATED,
      ),
    ).toBeNull();
  });

  it("an unknown window start falls back to the dated form, which is true either way", () => {
    expect(calibrationMeta(cal({}), null)).toBe(
      "60,800 graded, Sep 9 to Oct 8",
    );
  });

  it("formats ISO calendar days without a timezone shift", () => {
    expect(formatIsoDay("2026-10-01")).toBe("Oct 1");
  });
});
