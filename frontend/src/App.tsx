import { Anchor, AppShell, Container, Group, Title } from "@mantine/core";
import {
  BrowserRouter,
  NavLink,
  Outlet,
  Route,
  Routes,
} from "react-router-dom";
import AboutPage from "./pages/about-page";
import { GamePage, TodaysGamesPage } from "./pages/game-page";
import HomePage from "./pages/home-page";
import OpsPage from "./pages/ops-page";
import ParksPage from "./pages/parks-page";
import ParksToyPage from "./pages/parks-toy-page";
import PlayersPage, { PlayerProfilePage } from "./pages/players-page";

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
        <Outlet />
      </AppShell.Main>
    </AppShell>
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
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
