import { Container, SimpleGrid, Stack, Text, Title } from "@mantine/core";
import examplesJson from "../data/example-bbips.json";
import {
  ExampleBbipCard,
  type ExampleBbip,
} from "../components/parks/example-bbip-card";

const examples = examplesJson as ExampleBbip[];

/** Phase-1.6 toy demo, kept at /parks/toy after 4c.2's marquee page took over /parks. */
export default function ParksToyPage() {
  return (
    <Container size="lg" py="xl">
      <Stack gap="md">
        <Title order={1}>Park Explorer — toy examples</Title>
        <Text c="dimmed">
          Ten hand-picked 2024 batted balls. Click <em>Predict</em> to ask the
          toy model how often a ball with these inputs becomes a home run. The
          serving path (Spring + ONNX Runtime + the Phase-1 toy LightGBM model)
          is real; the model itself is a 5-feature plumbing build that gets
          replaced in Phase 2.
        </Text>
        <SimpleGrid cols={{ base: 1, sm: 2, lg: 3 }} spacing="md">
          {examples.map((ex) => (
            <ExampleBbipCard key={ex.id} example={ex} />
          ))}
        </SimpleGrid>
      </Stack>
    </Container>
  );
}
