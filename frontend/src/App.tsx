import {
  Anchor,
  AppShell,
  Container,
  Group,
  Loader,
  Title,
} from "@mantine/core";
import { lazy, Suspense } from "react";
import {
  BrowserRouter,
  NavLink,
  Outlet,
  Route,
  Routes,
} from "react-router-dom";

import HomePage from "./pages/home-page";
import { TokenSampleCard } from "./components/_token-sample";

/**
 * Every non-home page is lazy-loaded so the initial chunk is just the layout
 * shell + home page. React Router resolves the route, Suspense shows a small
 * loader while the route's chunk fetches, then the page renders. Pages with
 * named (non-default) exports are mapped to a `{ default: NamedExport }` shape
 * because React's `lazy` requires a default export contract.
 */
const AboutPage = lazy(() => import("./pages/about-page"));
const ParksPage = lazy(() => import("./pages/parks-page"));
const ParksToyPage = lazy(() => import("./pages/parks-toy-page"));
const OpsPage = lazy(() => import("./pages/ops-page"));

const PlayersPage = lazy(() => import("./pages/players-page"));
const PlayerProfilePage = lazy(() =>
  import("./pages/players-page").then((m) => ({
    default: m.PlayerProfilePage,
  })),
);

const TodaysGamesPage = lazy(() =>
  import("./pages/game-page").then((m) => ({ default: m.TodaysGamesPage })),
);
const GamePage = lazy(() =>
  import("./pages/game-page").then((m) => ({ default: m.GamePage })),
);

function Layout() {
  return (
    <AppShell header={{ height: 56 }} padding={0}>
      <AppShell.Header>
        <Container size="lg" h="100%">
          <Group h="100%" justify="space-between">
            <Title order={3} style={{ fontWeight: 700 }}>
              the bullpen
            </Title>
            <Group gap="md">
              <Anchor component={NavLink} to="/" end>
                home
              </Anchor>
              <Anchor component={NavLink} to="/parks">
                parks
              </Anchor>
              <Anchor component={NavLink} to="/players">
                players
              </Anchor>
              <Anchor component={NavLink} to="/games">
                games
              </Anchor>
              <Anchor component={NavLink} to="/ops">
                ops
              </Anchor>
              <Anchor component={NavLink} to="/about">
                about
              </Anchor>
            </Group>
          </Group>
        </Container>
      </AppShell.Header>
      <AppShell.Main>
        <Suspense fallback={<RoutePending />}>
          <Outlet />
        </Suspense>
      </AppShell.Main>
    </AppShell>
  );
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
          <Route path="parks/toy" element={<ParksToyPage />} />
          <Route path="players" element={<PlayersPage />} />
          <Route path="players/:id" element={<PlayerProfilePage />} />
          <Route path="games" element={<TodaysGamesPage />} />
          <Route path="games/:id" element={<GamePage />} />
          <Route path="ops" element={<OpsPage />} />
          <Route path="about" element={<AboutPage />} />
          <Route path="tokens" element={<TokenSampleCard />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
