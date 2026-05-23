import { Anchor, AppShell, Container, Group, Title } from "@mantine/core";
import {
  BrowserRouter,
  NavLink,
  Outlet,
  Route,
  Routes,
} from "react-router-dom";
import HomePage from "./pages/home-page";
import ParksPage from "./pages/parks-page";

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
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
