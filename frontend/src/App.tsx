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
} from "react-router-dom";

import HomePage from "./pages/home-page";
import { ErrorBoundary } from "./components/shared/error-boundary";
import { colors, typography } from "./design/broadcast";

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

// Unlisted in the public nav — operator routing override (B7), reached by URL.
const AdminRoutingPage = lazy(() => import("./pages/admin-routing-page"));

// S6 — catch-all 404 for any unmatched URL (otherwise the shell renders blank).
const NotFoundPage = lazy(() => import("./pages/not-found-page"));

// Broadcast chrome nav ([160] cleanup PR): the global frame is the dark
// telecast masthead - wordmark in Barlow italic with a gold tick, nav links
// on chrome. Team color never appears here (the frame is brand, not matchup).
const navLinkStyle: React.CSSProperties = {
  fontFamily: typography.fonts.display,
  fontStyle: "italic",
  fontWeight: typography.weights.semibold,
  fontSize: 15,
  letterSpacing: "0.06em",
  textTransform: "uppercase",
  color: colors.textOnChrome,
  textDecoration: "none",
};

// One nav source for both surfaces (horizontal bar >= sm, drawer below sm - D1 mobile nav).
const NAV_ITEMS: ReadonlyArray<{ to: string; label: string; end?: boolean }> = [
  { to: "/", label: "home", end: true },
  { to: "/parks", label: "parks" },
  { to: "/players", label: "players" },
  { to: "/games", label: "games" },
  { to: "/ops", label: "ops" },
  { to: "/accuracy", label: "accuracy" },
  { to: "/about", label: "about" },
];

function Layout() {
  // D1: below the `sm` breakpoint the 7-link bar cannot fit the 56px header, so the horizontal
  // group swaps for a burger + chrome drawer (Mantine visibleFrom/hiddenFrom - no JS media logic).
  const [navOpen, { toggle: toggleNav, close: closeNav }] =
    useDisclosure(false);
  return (
    <AppShell header={{ height: 56 }} padding={0}>
      <AppShell.Header
        style={{
          backgroundColor: colors.chrome,
          borderBottom: `2px solid ${colors.gold}`,
        }}
      >
        <Container size="lg" h="100%">
          <Group h="100%" justify="space-between">
            <Group gap={8}>
              <span
                aria-hidden="true"
                style={{
                  width: 6,
                  height: 22,
                  backgroundColor: colors.gold,
                  display: "inline-block",
                }}
              />
              <Title
                order={3}
                style={{
                  fontFamily: typography.fonts.display,
                  fontStyle: "italic",
                  fontWeight: typography.weights.heavy,
                  letterSpacing: "0.04em",
                  textTransform: "uppercase",
                  color: colors.textOnChrome,
                }}
              >
                The Bullpen
              </Title>
            </Group>
            <Group gap="md" visibleFrom="sm">
              {NAV_ITEMS.map((item) => (
                <Anchor
                  key={item.to}
                  component={NavLink}
                  to={item.to}
                  end={item.end}
                  style={navLinkStyle}
                >
                  {item.label}
                </Anchor>
              ))}
            </Group>
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
          content: { backgroundColor: colors.chrome },
          header: {
            backgroundColor: colors.chrome,
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
          {NAV_ITEMS.map((item) => (
            <Anchor
              key={item.to}
              component={NavLink}
              to={item.to}
              end={item.end}
              onClick={closeNav}
              style={{ ...navLinkStyle, fontSize: 20, padding: "6px 0" }}
            >
              {item.label}
            </Anchor>
          ))}
        </Stack>
      </Drawer>
      <AppShell.Main>
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
          <Route path="admin/routing" element={<AdminRoutingPage />} />
          <Route path="about" element={<AboutPage />} />
          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
