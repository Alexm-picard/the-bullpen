/**
 * The live park-name map (SPEC-parks section 5, decision: fix the AZ / ATH gap).
 *
 * Oracle: the frontend's own team list (DIVISIONS, the players index), which the backend's live
 * ingest map is documented to match ("AZ not ARI, ATH not OAK, CWS not CHW"). The training park id
 * IS the home team code, so the 30 serving ids are the 30 team codes. The map is the subject; the
 * team list is an independent definition it must agree with.
 */
import { describe, expect, it } from "vitest";

import { PARK_ROWS } from "../data/parks-fixtures";

import { DIVISIONS } from "./player-file";
import { SERVING_PARK_IDS, parkName } from "./park-names";

const TEAMS = DIVISIONS.flatMap((d) => d.teams);

describe("park-names", () => {
  it("covers exactly the 30 current team codes, with a real name for each", () => {
    expect([...SERVING_PARK_IDS].sort()).toEqual([...TEAMS].sort());
    for (const id of SERVING_PARK_IDS) {
      expect(parkName(id)).not.toBe(id);
    }
  });

  it("names the two codes the old fixture map lacked", () => {
    expect(parkName("AZ")).toBe("Chase Field");
    expect(parkName("ATH")).toBe("Sutter Health Park");
  });

  it("names a legacy code for the park its rows were hit in, never relabelled", () => {
    expect(parkName("ARI")).toBe("Chase Field");
    expect(parkName("CHW")).toBe(parkName("CWS"));
    // OAK rows are Oakland Coliseum seasons; they must not read as the Athletics' current park.
    expect(parkName("OAK")).toBe("Oakland Coliseum");
  });

  it("prints an unknown id as itself rather than guessing", () => {
    expect(parkName("XYZ")).toBe("XYZ");
  });

  it("agrees with the showcase fixture's names and ids (one spelling per park on the page)", () => {
    expect(PARK_ROWS.map((r) => r.id).sort()).toEqual([...TEAMS].sort());
    for (const r of PARK_ROWS) expect(r.parkName).toBe(parkName(r.id));
  });
});
