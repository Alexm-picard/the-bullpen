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
import { colors, motion, radii, typography } from "./design/broadcast";
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

// Broadcast chrome nav ([160] cleanup PR): the global frame is the dark
// telecast masthead - wordmark in Barlow italic with a gold tick, nav links
// [191] journey-order nav: primary surfaces first, Models group second, ops/about last.
const navLinkStyle: React.CSSProperties = {
  fontFamily: typography.fonts.display,
  fontWeight: typography.weights.semibold,
  fontSize: 15,
  letterSpacing: typography.tracking.chip,
  textTransform: "uppercase",
  height: 56,
  display: "inline-flex",
  alignItems: "center",
  padding: "0 2px",
  marginBottom: -2,
  whiteSpace: "nowrap",
  flexShrink: 0,
};

// Color, the 3px rule and the current-page state live in `.bp-navlink`
// (interaction.css): an inline color would outrank its :hover rule.
// No `padding` shorthand here: `.bp-navlink--drawer` owns padding-left (the
// gap beside its gold current-page rail).
const drawerLinkStyle: React.CSSProperties = {
  fontFamily: typography.fonts.display,
  fontWeight: typography.weights.semibold,
  fontSize: 20,
  letterSpacing: typography.tracking.section,
  textTransform: "uppercase",
  display: "inline-flex",
  alignItems: "center",
  paddingTop: 6,
  paddingBottom: 6,
  whiteSpace: "nowrap",
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
  fontSize: 11,
  letterSpacing: typography.tracking.eyebrow,
  fontWeight: typography.weights.semibold,
  whiteSpace: "nowrap",
  color: colors.textOnChromeMuted,
  textTransform: "uppercase",
};

const groupSeparatorStyle: React.CSSProperties = {
  borderLeft: `1px solid ${colors.chromeEdge}`,
  paddingLeft: 16,
};

function ThemeToggleButton() {
  const { theme, toggle } = useTheme();
  return (
    <button
      onClick={toggle}
      aria-label={`Switch to ${theme === "dark" ? "light" : "dark"} mode`}
      title={theme === "dark" ? "Switch to light mode" : "Switch to dark mode"}
      // `.bp-theme-toggle` owns the border (and its gold :hover) and the
      // transition; `.bp-pressable` owns the press scale. 44px hit box.
      className="bp-pressable bp-theme-toggle"
      style={{
        background: "none",
        borderRadius: radii.sm,
        width: 44,
        height: 44,
        flexShrink: 0,
        fontSize: 18,
        color: colors.gold,
        display: "inline-flex",
        alignItems: "center",
        justifyContent: "center",
      }}
    >
      {theme === "dark" ? "☀" : "☾"}
    </button>
  );
}

function Layout() {
  // D1: below the `md` breakpoint (62em) the 8-link bar cannot fit the 56px header, so the horizontal
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
          backgroundColor: colors.chrome,
          borderBottom: `2px solid ${colors.gold}`,
          paddingLeft: "env(safe-area-inset-left)",
          paddingRight: "env(safe-area-inset-right)",
        }}
      >
        <Container size="lg" h="100%">
          <Group h="100%" justify="space-between" wrap="nowrap">
            <Title
              order={3}
              style={{
                fontFamily: typography.fonts.display,
                fontWeight: typography.weights.heavy,
                fontSize: 21,
                letterSpacing: "0.03em",
                textTransform: "uppercase",
                color: colors.textOnChrome,
                whiteSpace: "nowrap",
                flexShrink: 0,
              }}
            >
              The <span style={{ color: colors.gold }}>Bullpen</span>
            </Title>
            <Group gap="md" wrap="nowrap" visibleFrom="md">
              {NAV_ENTRIES.map((entry) =>
                isGroup(entry) ? (
                  <Group
                    key={entry.groupLabel}
                    gap="md"
                    wrap="nowrap"
                    style={groupSeparatorStyle}
                  >
                    <span style={groupLabelStyle}>{entry.groupLabel}</span>
                    {entry.items.map((item) => (
                      <Anchor
                        key={item.to}
                        component={NavLink}
                        to={item.to}
                        end={item.end}
                        className="bp-navlink"
                        underline="never"
                        style={navLinkStyle}
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
                    className="bp-navlink"
                    underline="never"
                    style={navLinkStyle}
                  >
                    {entry.label}
                  </Anchor>
                ),
              )}
            </Group>
            <ThemeToggleButton />
            <Burger
              hiddenFrom="md"
              opened={navOpen}
              onClick={toggleNav}
              aria-label="Toggle navigation"
              color={colors.textOnChrome}
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
        overlayProps={{ backgroundOpacity: 0.55, blur: 2 }}
        styles={{
          content: {
            backgroundColor: colors.chrome,
            paddingRight: "env(safe-area-inset-right)",
            paddingBottom: "env(safe-area-inset-bottom)",
          },
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
              className="bp-navlink bp-navlink--drawer"
              underline="never"
              style={drawerLinkStyle}
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
