import {
  AppShell,
  Burger,
  Container,
  Drawer,
  Group,
  Loader,
  Stack,
} from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import { lazy, Suspense, type ReactNode } from "react";
import {
  BrowserRouter,
  NavLink,
  Outlet,
  Route,
  Routes,
  useLocation,
} from "react-router";

import HomePage from "./pages/home-page";
import { ErrorBoundary } from "./components/shared/error-boundary";
import { motion } from "./design/broadcast";
import { ThemeProvider } from "./design/theme-provider";
import { useTheme } from "./design/use-theme";

/**
 * Every non-home page is lazy-loaded so the initial chunk is just the layout
 * shell + home page. React Router resolves the route, Suspense shows a small
 * loader while the route's chunk fetches, then the page renders. Pages with
 * named (non-default) exports are mapped to a `{ default: NamedExport }` shape
 * because React's `lazy` requires a default export contract.
 */
const AboutPage = lazy(() => import("./pages/about-page"));
const ParksPage = lazy(() => import("./pages/parks-page"));
const OpsPage = lazy(() => import("./pages/ops/ops-page"));
const AccuracyPage = lazy(() => import("./pages/accuracy-page"));

const PlayersPage = lazy(() => import("./pages/players-page"));
const PlayerProfilePage = lazy(() => import("./pages/player-profile-page"));

const GamesPage = lazy(() => import("./pages/games-page"));
const GamePage = lazy(() =>
  import("./pages/game-page").then((m) => ({ default: m.GamePage })),
);

// [191] Model Guide - the reading surface (paper palette).
const ModelGuidePage = lazy(() => import("./pages/model-guide-page"));

// Unlisted in the public nav - operator routing override (B7), reached by URL.
const AdminRoutingPage = lazy(() => import("./pages/admin-routing-page"));

// S6 — catch-all 404 for any unmatched URL (otherwise the shell renders blank).
const NotFoundPage = lazy(() => import("./pages/not-found-page"));

// [195] editorial chrome: one translucent bar (content scrolls under it, a scroll-edge fade in
// place of a divider), serif wordmark, sentence-case links in Inter. [191]'s journey-order IA is
// unchanged: primary surfaces first, the Models group second, ops/about last.
type NavItem = { to: string; label: string; end?: boolean };
type NavGroup = { groupLabel: string; items: NavItem[] };
type NavEntry = NavItem | NavGroup;

function isGroup(e: NavEntry): e is NavGroup {
  return "groupLabel" in e;
}

const NAV_ENTRIES: ReadonlyArray<NavEntry> = [
  // "Tonight", not "Home": name the page by what is on it (SPEC-home §13 answer 4).
  { to: "/", label: "Tonight", end: true },
  { to: "/games", label: "Games" },
  { to: "/players", label: "Players" },
  {
    groupLabel: "Models",
    items: [
      { to: "/accuracy", label: "Accuracy" },
      { to: "/parks", label: "Parks" },
      { to: "/models/guide", label: "Guide" },
    ],
  },
  { to: "/ops", label: "Ops" },
  { to: "/about", label: "About" },
];

const ALL_NAV_ITEMS: NavItem[] = NAV_ENTRIES.flatMap((e) =>
  isGroup(e) ? e.items : [e],
);

/**
 * [192] toggle, [195] labels: the paper is the "Day edition", the dark variant the "Night
 * edition". The button names the edition it switches TO; aria-pressed reports whether the night
 * edition is on, so the state is announced without relying on the label alone.
 */
function ThemeToggleButton() {
  const { theme, toggle } = useTheme();
  const night = theme === "dark";
  return (
    <button
      type="button"
      onClick={toggle}
      aria-pressed={night}
      aria-label="Night edition"
      className="ed-toggle"
    >
      <span aria-hidden="true">{night ? "Day edition" : "Night edition"}</span>
    </button>
  );
}

function NavLinks({ onNavigate }: { onNavigate?: () => void }) {
  return (
    <>
      {NAV_ENTRIES.map((entry) =>
        isGroup(entry) ? (
          <div key={entry.groupLabel} className="ed-navgroup">
            <span className="ed-navgroup__label">{entry.groupLabel}</span>
            {entry.items.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.end}
                className="ed-navlink"
                onClick={onNavigate}
              >
                {item.label}
              </NavLink>
            ))}
          </div>
        ) : (
          <NavLink
            key={entry.to}
            to={entry.to}
            end={entry.end}
            className="ed-navlink"
            onClick={onNavigate}
          >
            {entry.label}
          </NavLink>
        ),
      )}
    </>
  );
}

function Layout() {
  // Below `md` (62em) the link row cannot fit; it moves into a sheet that enters from the right
  // and leaves to the right (same edge both ways), faster out than in.
  const [navOpen, { toggle: toggleNav, close: closeNav }] =
    useDisclosure(false);
  return (
    <AppShell header={{ height: 56 }} padding={0}>
      {/* D4 (a11y): first focusable element - keyboard users bypass the nav chrome. */}
      <a href="#main-content" className="skip-link">
        Skip to content
      </a>
      <AppShell.Header
        withBorder={false}
        className="ed-header"
        style={{
          paddingLeft: "env(safe-area-inset-left)",
          paddingRight: "env(safe-area-inset-right)",
        }}
      >
        <Container size="xl" h="100%">
          <Group h="100%" justify="space-between" wrap="nowrap" gap="lg">
            <Group gap="xl" wrap="nowrap">
              <NavLink
                to="/"
                className="ed-wordmark"
                aria-label="The Bullpen, home"
              >
                The Bullpen
              </NavLink>
              <nav aria-label="Primary">
                <Group gap="lg" wrap="nowrap" visibleFrom="md">
                  <NavLinks />
                </Group>
              </nav>
            </Group>
            <Group gap="xs" wrap="nowrap">
              <ThemeToggleButton />
              <Burger
                hiddenFrom="md"
                opened={navOpen}
                onClick={toggleNav}
                aria-label="Toggle navigation"
                color="var(--ed-ink)"
                size="sm"
                transitionDuration={motion.durationsMs.press}
                transitionTimingFunction={motion.easing.out}
                styles={{
                  root: {
                    width: 44,
                    height: 44,
                    flexShrink: 0,
                    display: "flex",
                    alignItems: "center",
                    justifyContent: "center",
                  },
                }}
              />
            </Group>
          </Group>
        </Container>
      </AppShell.Header>
      <Drawer
        opened={navOpen}
        onClose={closeNav}
        position="right"
        size="xs"
        hiddenFrom="md"
        title="The Bullpen"
        transitionProps={{
          duration: motion.durationsMs.slow,
          exitDuration: motion.durationsMs.base,
          timingFunction: motion.easing.drawer,
        }}
        overlayProps={{ backgroundOpacity: 0.35, blur: 2 }}
        styles={{
          content: {
            backgroundColor: "var(--ed-ground)",
            paddingRight: "env(safe-area-inset-right)",
            paddingBottom: "env(safe-area-inset-bottom)",
          },
          header: {
            backgroundColor: "var(--ed-ground)",
            borderBottom: "1px solid var(--ed-rule)",
          },
          title: {
            fontFamily: "var(--ed-serif)",
            fontWeight: 700,
            fontSize: "1.25rem",
            color: "var(--ed-ink)",
          },
          close: { color: "var(--ed-ink)" },
        }}
      >
        <Stack gap={0} pt="sm">
          {ALL_NAV_ITEMS.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              onClick={closeNav}
              className="ed-navlink ed-navlink--drawer"
            >
              {item.label}
            </NavLink>
          ))}
        </Stack>
      </Drawer>
      <AppShell.Main id="main-content">
        <RouteBoundary>
          <Suspense fallback={<RoutePending />}>
            <Outlet />
          </Suspense>
        </RouteBoundary>
      </AppShell.Main>
    </AppShell>
  );
}

/**
 * Route-level boundary: keyed on the pathname so navigating to another route
 * remounts it and clears a prior page's error. Keeps the AppShell header/nav
 * visible when a single page throws, so the visitor can navigate away rather
 * than reload.
 */
function RouteBoundary({ children }: { children: ReactNode }) {
  const location = useLocation();
  return <ErrorBoundary key={location.pathname}>{children}</ErrorBoundary>;
}

function RoutePending() {
  return (
    <Container
      size="lg"
      py="xl"
      role="status"
      aria-label="Loading page"
      style={{ minHeight: "60vh" }}
    >
      {/* `.route-pending` stays invisible for 300ms: a chunk that resolves
          inside that window never flashes a spinner. */}
      <Group justify="center" py="xl" className="route-pending">
        <Loader size="sm" color="gold" />
      </Group>
    </Container>
  );
}

export default function App() {
  return (
    <ThemeProvider>
      <BrowserRouter>
        <Routes>
          <Route element={<Layout />}>
            <Route index element={<HomePage />} />
            <Route path="parks" element={<ParksPage />} />
            <Route path="players" element={<PlayersPage />} />
            <Route path="players/:id" element={<PlayerProfilePage />} />
            <Route path="games" element={<GamesPage />} />
            <Route path="games/:id" element={<GamePage />} />
            <Route path="ops" element={<OpsPage />} />
            <Route path="accuracy" element={<AccuracyPage />} />
            <Route path="models/guide" element={<ModelGuidePage />} />
            <Route path="admin/routing" element={<AdminRoutingPage />} />
            <Route path="about" element={<AboutPage />} />
            <Route path="*" element={<NotFoundPage />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </ThemeProvider>
  );
}
