/**
 * Unit test for the timestamp formatter — the only piece of pure logic in the
 * table component. Full render snapshotting needs Mantine + jsdom; not worth the
 * deps for a presentational component.
 */
import { describe, expect, it } from "vitest";

import { formatTimestamp } from "./format-timestamp";

describe("formatTimestamp", () => {
  it("renders ISO instant as YYYY-MM-DD HH:MM UTC", () => {
    expect(formatTimestamp("2026-05-20T18:30:00Z")).toBe(
      "2026-05-20 18:30 UTC",
    );
  });

  it("zero-pads months/days/hours/minutes", () => {
    expect(formatTimestamp("2026-01-05T07:09:00Z")).toBe(
      "2026-01-05 07:09 UTC",
    );
  });

  it("returns the raw input on unparseable strings", () => {
    expect(formatTimestamp("not-a-date")).toBe("not-a-date");
  });
});
