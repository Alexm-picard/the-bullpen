/**
 * Player Lookup landing page (leaf 4b.1).
 *
 * Search-only here. Selecting a result navigates to `/players/:id`, which
 * leaf 4b.2 will render. Until 4b.2 lands, the deep-link page is a placeholder
 * showing the resolved id and name.
 */
import { Container, Stack, Text, Title } from "@mantine/core";
import { useNavigate, useParams } from "react-router-dom";

import { usePlayer } from "../api/players";
import { PlayerSearch } from "../components/players/player-search";

export default function PlayersPage() {
  const navigate = useNavigate();
  return (
    <Container size="md" py="xl">
      <Stack gap="md">
        <Title order={1}>Player Lookup</Title>
        <Text c="dimmed">
          Find a pitcher or hitter by name or Statcast ID. Roster is the active
          + historical MLB set; type at least one character to begin.
        </Text>
        <PlayerSearch
          autoFocus
          onSelect={(p) => {
            navigate(`/players/${p.id}`);
          }}
        />
      </Stack>
    </Container>
  );
}

/** Deep-link landing for /players/:id. Placeholder until 4b.2 lands. */
export function PlayerProfilePage() {
  const { id } = useParams<{ id: string }>();
  const numericId = id ? Number(id) : null;
  const valid = numericId != null && Number.isFinite(numericId);
  const { data, isLoading, isError, error } = usePlayer(
    valid ? numericId : null,
  );

  return (
    <Container size="md" py="xl">
      <Stack gap="md">
        <Title order={1}>
          {data ? data.name : valid ? `Player #${id}` : "Player profile"}
        </Title>
        {isLoading ? <Text c="dimmed">Loading…</Text> : null}
        {isError ? (
          <Text c="red">
            Could not load this player
            {error instanceof Error ? `: ${error.message}` : ""}.
          </Text>
        ) : null}
        {data ? (
          <Text>
            Position {data.primaryPosition} ·{" "}
            {data.active ? "Active" : "Retired"} · Statcast id {data.id}
          </Text>
        ) : null}
        <Text size="sm" c="dimmed">
          Full profile and outing history land in leaf 4b.2; pitch-by-pitch
          calibration plot in 4b.3.
        </Text>
      </Stack>
    </Container>
  );
}
