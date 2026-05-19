import { useQuery } from "@tanstack/react-query";
import { Badge, Container, Stack, Text, Title } from "@mantine/core";

type Health = { status: string; profile: string; ts: string };

const API_BASE = import.meta.env.VITE_API_BASE ?? "http://localhost:8080";

async function fetchHealth(): Promise<Health> {
  const res = await fetch(`${API_BASE}/health`);
  if (!res.ok) throw new Error(`health ${res.status}`);
  return (await res.json()) as Health;
}

export default function App() {
  const { data, error, isLoading } = useQuery({
    queryKey: ["health"],
    queryFn: fetchHealth,
    refetchInterval: 10_000,
  });

  return (
    <Container size="sm" py="xl">
      <Stack gap="md">
        <Title order={1}>The Bullpen</Title>
        <Text c="dimmed">
          Phase 0 vertical: React frontend → Spring <code>/health</code>.
        </Text>

        {isLoading && <Badge color="gray">checking…</Badge>}
        {error && <Badge color="red">backend unreachable</Badge>}
        {data && (
          <Stack gap={4}>
            <Badge color="green">backend {data.status}</Badge>
            <Text size="sm" c="dimmed">
              profile: <code>{data.profile}</code> · ts: <code>{data.ts}</code>
            </Text>
          </Stack>
        )}
      </Stack>
    </Container>
  );
}
