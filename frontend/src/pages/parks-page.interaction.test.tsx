// @vitest-environment jsdom
/**
 * /parks request-on-release (SPEC-parks 6.2, owner decision 2026-10-11).
 *
 * POST /v1/predict/batted-ball/all-parks logs EVERY request to prediction_log, so the page must send
 * one request per SETTLED ball. These tests count POSTs at the fetch boundary with the REAL Mantine
 * Slider and the real query, and prove:
 *   - mount: exactly 1 (the canonical ball);
 *   - a pointer drag across many values: 0 while dragging, exactly 1 on release, carrying the
 *     release value (mutation: keying the query on the DRAFT makes this red - every move would post);
 *   - a drag released at its starting value: 0;
 *   - a burst of key steps (or a held key's auto-repeat): 0 until KEY_COALESCE_MS after the LAST
 *     step, then exactly 1;
 *   - bat side: 1 immediately; back to a ball still in cache: 0;
 *   - the numbers on screen stay labelled with the ball they were computed for while a new one is
 *     in flight.
 * jsdom has no layout, so the slider track is given a 100px box (clientX 0..100 maps to min..max).
 */
import "@testing-library/jest-dom/vitest";

import {
  act,
  cleanup,
  fireEvent,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";

import { KEY_COALESCE_MS } from "../components/parks/use-ball-commit";
import { PHYSICS_CAVEAT_LEAD } from "../data/parks-claims";
import {
  installMantineShims,
  renderWithProviders,
} from "../test-support/behavioral";

import ParksPage from "./parks-page";

const IDS = ["COL", "LAD", "ATL", "CWS", "AZ", "ATH", "NYY", "SF"];
function response(seed = 0) {
  const probHrByPark: Record<string, number> = {};
  const carryFtByPark: Record<string, number> = {};
  IDS.forEach((id, i) => {
    probHrByPark[id] = Math.max(0, Math.min(1, 0.9 - i * 0.12 + seed));
    carryFtByPark[id] = 410 + i * 3;
  });
  return {
    probHrByPark,
    carryFtByPark,
    modelName: "battedball_outcome",
    modelVersion: "v2",
    latencyMicros: 1200,
    correlationId: "test",
  };
}

type Pending = { resolve: () => void };

/** Counts all-parks POSTs; optionally holds a response open until the test releases it. */
function stubAllParks(
  opts: { hold?: (n: number) => boolean; status?: number } = {},
) {
  const pending: Pending[] = [];
  const bodies: Record<string, unknown>[] = [];
  const fetchMock = vi.fn(
    async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === "string" ? input : input.toString();
      if (!url.includes("/v1/predict/batted-ball/all-parks")) {
        throw new Error(`unstubbed ${url}`);
      }
      bodies.push(JSON.parse(String(init?.body)) as Record<string, unknown>);
      const n = bodies.length;
      const status = opts.status ?? 200;
      const res = {
        ok: status < 400,
        status,
        json: async () => response(n === 1 ? 0 : -0.3),
      } as Response;
      if (opts.hold?.(n)) {
        return new Promise<Response>((resolve) =>
          pending.push({ resolve: () => resolve(res) }),
        );
      }
      return res;
    },
  );
  vi.stubGlobal("fetch", fetchMock);
  return { posts: () => bodies.length, bodies, pending };
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

beforeAll(() => {
  installMantineShims();
  // A 100px track: Mantine's useMove maps clientX / width to the slider fraction.
  vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockReturnValue({
    left: 0,
    top: 0,
    right: 100,
    bottom: 10,
    width: 100,
    height: 10,
    x: 0,
    y: 0,
    toJSON: () => ({}),
  } as DOMRect);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});

const answer = () =>
  screen.getByRole("region", { name: "This ball, in every park" });

async function mounted() {
  const net = stubAllParks();
  renderWithProviders(<ParksPage />);
  await waitFor(() => expect(net.posts()).toBe(1));
  await screen.findByText(/battedball_outcome v2, scored/);
  return net;
}

/** A pointer scrub on a slider: down at the first x, move through the rest, up at the last. */
async function drag(slider: HTMLElement, xs: number[]) {
  fireEvent.mouseDown(slider, { clientX: xs[0] });
  for (const x of xs.slice(1)) {
    fireEvent.mouseMove(document, { clientX: x });
    await act(() => sleep(20)); // let Mantine's rAF apply the move
  }
}
async function release() {
  fireEvent.mouseUp(document);
  await act(() => sleep(10)); // Mantine fires onChangeEnd on a setTimeout(0)
}

describe("/parks request on release", () => {
  it("mount sends exactly one request, for the canonical ball", async () => {
    const net = await mounted();
    expect(net.bodies[0]).toMatchObject({
      launchSpeedMph: 110,
      launchAngleDeg: 28,
      sprayAngleDeg: 0,
      stand: "R",
      hitDistanceFt: 400,
    });
    await act(() => sleep(KEY_COALESCE_MS + 100));
    expect(net.posts()).toBe(1);
  });

  it("a drag sends nothing while moving and exactly one request on release", async () => {
    const net = await mounted();
    const ev = screen.getByRole("slider", { name: "Exit velocity" });
    // 110 mph sits at x = (110-40)/85 = 82.35; scrub down through several values.
    await drag(ev, [82.35, 70, 60, 50, 40, 30]);
    expect(net.posts()).toBe(1);
    // Mid-drag the page says the numbers are the OLD ball's, and asks for a release.
    expect(
      within(answer()).getByText(/Release to rescore/),
    ).toBeInTheDocument();
    const shown = Number(ev.getAttribute("aria-valuenow"));
    expect(shown).not.toBe(110);
    await release();
    await waitFor(() => expect(net.posts()).toBe(2));
    expect(net.bodies[1]).toMatchObject({ launchSpeedMph: shown });
    await act(() => sleep(KEY_COALESCE_MS + 100));
    expect(net.posts()).toBe(2);
  });

  it("a drag released at its starting value sends nothing", async () => {
    const net = await mounted();
    const ev = screen.getByRole("slider", { name: "Exit velocity" });
    await drag(ev, [82.35, 50, 30, 82.35]);
    expect(ev).toHaveAttribute("aria-valuenow", "110");
    await release();
    await act(() => sleep(KEY_COALESCE_MS + 100));
    expect(net.posts()).toBe(1);
  });

  it("a burst of key steps (or a held key) sends one request, after the last step", async () => {
    const net = await mounted();
    const la = screen.getByRole("slider", { name: "Launch angle" });
    for (let i = 0; i < 5; i++) {
      // repeat: true on all but the first models a held key's auto-repeat.
      fireEvent.keyDown(la, { key: "ArrowUp", repeat: i > 0 });
      await act(() => sleep(50));
    }
    // 50 ms after the last step: still inside the coalesce window.
    expect(net.posts()).toBe(1);
    expect(la).toHaveAttribute("aria-valuenow", "33");
    await waitFor(() => expect(net.posts()).toBe(2), {
      timeout: KEY_COALESCE_MS + 500,
    });
    expect(net.bodies[1]).toMatchObject({ launchAngleDeg: 33 });
    await act(() => sleep(KEY_COALESCE_MS + 100));
    expect(net.posts()).toBe(2);
  });

  it("one key step sends exactly one request after the coalesce window", async () => {
    const net = await mounted();
    const dir = screen.getByRole("slider", { name: "Direction" });
    // Slider value is -spray: ArrowLeft moves the ball toward LEFT field (+ spray in the API).
    fireEvent.keyDown(dir, { key: "ArrowLeft" });
    expect(dir).toHaveAttribute("aria-valuetext", "1 degrees to left field");
    await act(() => sleep(KEY_COALESCE_MS - 150));
    expect(net.posts()).toBe(1);
    await waitFor(() => expect(net.posts()).toBe(2));
    expect(net.bodies[1]).toMatchObject({ sprayAngleDeg: 1 });
  });

  it("bat side sends at once; back to a ball still in cache sends nothing", async () => {
    const net = await mounted();
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Left" }));
    await waitFor(() => expect(net.posts()).toBe(2));
    expect(net.bodies[1]).toMatchObject({ stand: "L" });
    // The basis line and the polite live region both name the newly scored ball.
    expect(await screen.findAllByText(/left-handed batter/)).toHaveLength(2);
    await user.click(screen.getByRole("button", { name: "Right" }));
    await act(() => sleep(KEY_COALESCE_MS + 100));
    expect(net.posts()).toBe(2);
  });
});

describe("/parks scored labelling and states", () => {
  it("keeps the shown numbers labelled with the ball they were computed for while a new one is in flight", async () => {
    const net = stubAllParks({ hold: (n) => n === 2 });
    renderWithProviders(<ParksPage />);
    await screen.findByText(/battedball_outcome v2, scored/);
    const basis = () =>
      within(answer()).getByText(/right-handed batter$|left-handed batter$/);
    expect(basis()).toHaveTextContent(
      "110 mph, 28 deg up, straightaway, right-handed batter",
    );

    await userEvent.setup().click(screen.getByRole("button", { name: "Left" }));
    await waitFor(() => expect(net.posts()).toBe(2));
    // In flight: the figure still describes the RIGHT-handed ball, and the line says rescoring.
    expect(basis()).toHaveTextContent("right-handed batter");
    expect(
      within(answer()).getByText(/^Rescoring: .*left-handed batter\.$/),
    ).toBeInTheDocument();
    expect(answer()).toHaveAttribute("aria-busy", "true");

    act(() => net.pending[0]!.resolve());
    await waitFor(() =>
      expect(basis()).toHaveTextContent("left-handed batter"),
    );
    expect(answer()).not.toHaveAttribute("aria-busy");
  });

  it("an error shows no numbers, and the caveat stays", async () => {
    stubAllParks({ status: 500 });
    renderWithProviders(<ParksPage />);
    await screen.findByText(/Could not score this ball/);
    expect(within(answer()).queryByText(/of \d+$/)).toBeNull();
    expect(within(answer()).getByText(PHYSICS_CAVEAT_LEAD)).toBeInTheDocument();
    // Nothing is on its way, so nothing on the page promises a rescore.
    const field = screen.getByRole("region", { name: "The ball" });
    expect(within(field).queryByText(/rescor/i)).toBeNull();
    expect(within(answer()).queryByText(/rescor/i)).toBeNull();
  });

  it("counts parks with the game page's threshold and names AZ / ATH in the list", async () => {
    await mounted();
    // response(0): .90 .78 .66 .54 .42 .30 .18 .06 -> 4 at or above .50
    expect(within(answer()).getByText("4 of 8")).toBeInTheDocument();
    const list = screen.getByRole("list", {
      name: "This batted ball in every park",
    });
    expect(within(list).getByText("Chase Field")).toBeInTheDocument();
    expect(within(list).getByText("Sutter Health Park")).toBeInTheDocument();
  });
});
