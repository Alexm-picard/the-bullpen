// @vitest-environment jsdom
/**
 * Behavioral tests for <AdminRoutingPage> (W3.2). Exercises real interaction paths that the
 * project's renderToStaticMarkup unit tests structurally cannot: the in-memory credential gate
 * (Connect disabled until user + password, then the override panel appears) and the
 * reason-required audit friction (an Apply stays disabled until a reason is typed). Runs in a
 * per-file jsdom environment (the suite default is node); Mantine reads a few browser APIs jsdom
 * omits, stubbed below.
 */
import "@testing-library/jest-dom/vitest";

import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactElement } from "react";
import {
  afterEach,
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from "vitest";

import { theme } from "../design/theme";

vi.mock("../api/ops", () => ({ useRouting: vi.fn() }));
vi.mock("../api/admin", () => ({
  setRoutingMode: vi.fn(),
  setTrafficPct: vi.fn(),
  setChallenger: vi.fn(),
  clearChallenger: vi.fn(),
}));

import { useRouting } from "../api/ops";

import AdminRoutingPage from "./admin-routing-page";

type RoutingResult = ReturnType<typeof useRouting>;

const ROW = {
  modelName: "battedball_outcome",
  mode: "SHADOW",
  championVersionId: 1,
  challengerVersionId: null,
  challengerTrafficPct: 0,
};

function mockRouting(data: unknown[]): void {
  vi.mocked(useRouting).mockReturnValue({
    data,
    isLoading: false,
    isError: false,
  } as unknown as RoutingResult);
}

function renderPage(ui: ReactElement) {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={qc}>
      <MantineProvider theme={theme}>{ui}</MantineProvider>
    </QueryClientProvider>,
  );
}

beforeAll(() => {
  // Mantine (Select / SegmentedControl) reaches for these; jsdom does not implement them.
  window.matchMedia = ((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })) as unknown as typeof window.matchMedia;
  window.HTMLElement.prototype.scrollIntoView = vi.fn();
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
});

beforeEach(() => {
  mockRouting([ROW]);
});

// The suite runs without vitest `globals`, so testing-library's automatic afterEach cleanup is
// not registered - unmount + clear the DOM between tests explicitly.
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("AdminRoutingPage", () => {
  it("gates behind credentials: Connect is disabled until user + password are entered", async () => {
    const user = userEvent.setup();
    renderPage(<AdminRoutingPage />);

    const connect = screen.getByRole("button", { name: /connect/i });
    expect(connect).toBeDisabled();

    await user.type(screen.getByLabelText("User"), "ops");
    await user.type(screen.getByLabelText("Password"), "pw");
    expect(connect).toBeEnabled();

    await user.click(connect);
    // The override panel is only rendered once credentials are set.
    expect(
      screen.getByRole("button", { name: /disconnect/i }),
    ).toBeInTheDocument();
  });

  it("requires a reason before an Apply is enabled (per-change audit friction)", async () => {
    const user = userEvent.setup();
    renderPage(<AdminRoutingPage />);

    await user.type(screen.getByLabelText("User"), "ops");
    await user.type(screen.getByLabelText("Password"), "pw");
    await user.click(screen.getByRole("button", { name: /connect/i }));

    // Select the routed model via the keyboard (Mantine's Select dropdown can't be positioned in
    // jsdom, so a click-to-open + option-click is unreliable; ArrowDown + Enter selects the single
    // option deterministically) -> the override card appears.
    const modelInput = screen.getByPlaceholderText("Select a routed model");
    await user.click(modelInput);
    await user.keyboard("{ArrowDown}{Enter}");

    const applyMode = await screen.findByRole("button", {
      name: /apply mode/i,
    });
    expect(applyMode).toBeDisabled();

    await user.type(screen.getByLabelText(/reason/i), "cutover test");
    expect(applyMode).toBeEnabled();
  });
});
