import { describe, expect, it } from "vitest";

import { slateHeadline } from "./front-page-view";

describe("slateHeadline", () => {
  it("spells out small counts and pluralizes", () => {
    expect(slateHeadline(1)).toBe("One game tonight");
    expect(slateHeadline(3)).toBe("Three games tonight");
    expect(slateHeadline(15)).toBe("15 games tonight");
  });
  it("names an empty night plainly", () => {
    expect(slateHeadline(0)).toBe("Nothing scheduled");
  });
});
