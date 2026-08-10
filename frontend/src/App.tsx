import {
  Anchor,
  AppShell,
  Burger,
  Container,
  Drawer,
  Group,
  Loader,
  Stack,
  Title,
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
import { colors, typography } from "./design/broadcast";
import { ThemeProvider, useTheme } from "./design/theme-toggle";

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

// Broadcast chrome nav ([160] cleanup PR): the global frame is the dark
// telecast masthead - wordmark in Barlow italic with a gold tick, nav links
// [191] journey-order nav: primary surfaces first, Models group second, ops/about last.
const navLinkStyle: React.CSSProperties = {
  fontFamily: typography.fonts.display,
  fontWeight: typography.weights.semibold,
  fontSize: 15,
  letterSpacing: "0.08em",
  textTransform: "uppercase",
  color: colors.steel,
  textDecoration: "none",
  height: 56,
  display: "inline-flex",
  alignItems: "center",
  padding: "0 2px",
  borderBottom: "3px solid transparent",
  marginBottom: -2,
};

const navLinkActiveStyle: React.CSSProperties = {
  color: colors.ink,
  borderBottomColor: colors.gold,
};

type NavItem = { to: string; label: string; end?: boolean };
type NavGroup = { groupLabel: string; items: NavItem[] };
type NavEntry = NavItem | NavGroup;

function isGroup(e: NavEntry): e is NavGroup {
  return "groupLabel" in e;
}

const NAV_ENTRIES: ReadonlyArray<NavEntry> = [
  { to: "/", label: "home", end: true },
  { to: "/games", label: "games" },
  { to: "/players", label: "players" },
  {
    groupLabel: "Models",
    items: [
      { to: "/accuracy", label: "accuracy" },
      { to: "/parks", label: "parks" },
      { to: "/models/guide", label: "guide" },
    ],
  },
  { to: "/ops", label: "ops" },
  { to: "/about", label: "about" },
];

const ALL_NAV_ITEMS: NavItem[] = NAV_ENTRIES.flatMap((e) =>
  isGroup(e) ? e.items : [e],
);

const groupLabelStyle: React.CSSProperties = {
  fontFamily: typography.fonts.mono,
  fontSize: 9,
  letterSpacing: "0.18em",
  color: colors.textMuted,
  textTransform: "uppercase",
};

const groupSeparatorStyle: React.CSSProperties = {
  borderLeft: `1px solid ${colors.rule}`,
  paddingLeft: 16,
};

function ThemeToggleButton() {
  const { theme, toggle } = useTheme();
  return (
    <button
      onClick={toggle}
      aria-label={`Switch to ${theme === "dark" ? "light" : "dark"} mode`}
      title={theme === "dark" ? "Switch to light mode" : "Switch to dark mode"}
      style={{
        background: "none",
        border: `1px solid ${colors.panelEdge}`,
        borderRadius: 4,
        padding: "6px 12px",
        cursor: "pointer",
        fontFamily: typography.fonts.display,
        fontWeight: typography.weights.semibold,
        fontSize: 18,
        color: colors.gold,
        display: "inline-flex",
        alignItems: "center",
        gap: 6,
        transition: "border-color 0.15s",
      }}
    >
      {theme === "dark" ? "☀" : "☾"}
    </button>
  );
}

function Layout() {
  // D1: below the `sm` breakpoint the 7-link bar cannot fit the 56px header, so the horizontal
  // group swaps for a burger + chrome drawer (Mantine visibleFrom/hiddenFrom - no JS media logic).
  const [navOpen, { toggle: toggleNav, close: closeNav }] =
    useDisclosure(false);
  return (
    <AppShell header={{ height: 56 }} padding={0}>
      {/* D4 (a11y): first focusable element - keyboard users bypass the nav chrome. */}
      <a href="#main-content" className="skip-link">
        Skip to content
      </a>
      <AppShell.Header
        style={{
          backgroundColor: "var(--bp-field-hi)",
          borderBottom: `2px solid ${colors.gold}`,
        }}
      >
        <Container size="lg" h="100%">
          <Group h="100%" justify="space-between">
            <Title
              order={3}
              style={{
                fontFamily: typography.fonts.display,
                fontWeight: typography.weights.heavy,
                fontSize: 21,
                letterSpacing: "0.03em",
                textTransform: "uppercase",
                color: colors.ink,
              }}
            >
              The <span style={{ color: colors.gold }}>Bullpen</span>
            </Title>
            <Group gap="md" visibleFrom="sm">
              {NAV_ENTRIES.map((entry) =>
                isGroup(entry) ? (
                  <Group
                    key={entry.groupLabel}
                    gap="md"
                    style={groupSeparatorStyle}
                  >
                    <span style={groupLabelStyle}>{entry.groupLabel}</span>
                    {entry.items.map((item) => (
                      <Anchor
                        key={item.to}
                        component={NavLink}
                        to={item.to}
                        end={item.end}
                        style={({ isActive }: { isActive: boolean }) => ({
                          ...navLinkStyle,
                          ...(isActive ? navLinkActiveStyle : {}),
                        })}
                      >
                        {item.label}
                      </Anchor>
                    ))}
                  </Group>
                ) : (
                  <Anchor
                    key={entry.to}
                    component={NavLink}
                    to={entry.to}
                    end={entry.end}
                    style={({ isActive }: { isActive: boolean }) => ({
                      ...navLinkStyle,
                      ...(isActive ? navLinkActiveStyle : {}),
                      ...(entry.label === "about" ? { fontSize: 13 } : {}),
                    })}
                  >
                    {entry.label}
                  </Anchor>
                ),
              )}
            </Group>
            <ThemeToggleButton />
            <Burger
              hiddenFrom="sm"
              opened={navOpen}
              onClick={toggleNav}
              aria-label="Toggle navigation"
              color={colors.textOnChrome}
              size="sm"
            />
          </Group>
        </Container>
      </AppShell.Header>
      <Drawer
        opened={navOpen}
        onClose={closeNav}
        position="right"
        size="xs"
        hiddenFrom="sm"
        title="The Bullpen"
        styles={{
          content: { backgroundColor: "var(--bp-field-hi)" },
          header: {
            backgroundColor: "var(--bp-field-hi)",
            borderBottom: `2px solid ${colors.gold}`,
          },
          title: {
            fontFamily: typography.fonts.display,
            fontStyle: "italic",
            fontWeight: typography.weights.heavy,
            letterSpacing: "0.04em",
            textTransform: "uppercase",
            color: colors.textOnChrome,
          },
          close: { color: colors.textOnChrome },
        }}
      >
        <Stack gap="sm" pt="sm">
          {ALL_NAV_ITEMS.map((item) => (
            <Anchor
              key={item.to}
              component={NavLink}
              to={item.to}
              end={item.end}
              onClick={closeNav}
              style={{
                ...navLinkStyle,
                fontSize: 20,
                padding: "6px 0",
                height: "auto",
                borderBottom: "none",
                marginBottom: 0,
              }}
            >
              {item.label}
            </Anchor>
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
    <Container size="lg" py="xl">
      <Group justify="center" py="xl">
        <Loader size="sm" />
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
