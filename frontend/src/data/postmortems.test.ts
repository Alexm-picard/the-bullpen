import { describe, expect, it } from "vitest";

import {
  POSTMORTEMS,
  latestPostmortem,
  postmortemHref,
  type PostmortemEntry,
} from "./postmortems";

// The keys are the on-disk paths; the docs are never loaded (no eager import).
const ON_DISK = Object.keys(import.meta.glob("../../../docs/postmortems/*.md"));

describe("postmortem shelf", () => {
  it("lists every write-up in docs/postmortems (a new file must be shelved)", () => {
    const onDisk = ON_DISK.map((p) => p.split("/").pop() ?? p)
      .filter((f) => f !== "README.md")
      .sort();
    expect(onDisk.length).toBeGreaterThan(0);
    expect(POSTMORTEMS.map((p) => p.file).sort()).toEqual(onDisk);
  });

  it("the desk rotates to the newest write-up by date", () => {
    expect(latestPostmortem()?.file).toBe(
      "2026-08-03_pitcher-form-silent-staleness.md",
    );
    const newer: PostmortemEntry = {
      title: "t",
      dek: "d",
      kind: "postmortem",
      date: "2027-05-01",
      file: "2027-05-01_x.md",
    };
    expect(latestPostmortem([...POSTMORTEMS, newer])?.file).toBe(
      "2027-05-01_x.md",
    );
    expect(latestPostmortem([])).toBeNull();
  });

  it("links to the file on GitHub", () => {
    const first = latestPostmortem();
    if (!first) throw new Error("empty shelf");
    expect(postmortemHref(first)).toMatch(
      /^https:\/\/github\.com\/.+\/blob\/main\/docs\/postmortems\/2026-08-03_/,
    );
  });
});
