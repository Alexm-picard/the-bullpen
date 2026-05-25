/**
 * Ops dashboard (leaves 4e.1 – 4e.5).
 *
 * Five tabs: Registry, Drift, A/B routing, Retrain queue, Reliability. Public
 * page per decision [99] — no auth, recruiter-facing. Each tab is a self-
 * contained section under `components/ops/` so future leaves can polish each
 * independently.
 */
import { Container, Stack, Tabs, Text, Title } from "@mantine/core";

import { DriftSection } from "../components/ops/drift-section";
import { RegistrySection } from "../components/ops/registry-section";
import { ReliabilitySection } from "../components/ops/reliability-section";
import { RetrainQueueSection } from "../components/ops/retrain-queue-section";
import { RoutingSection } from "../components/ops/routing-section";

export default function OpsPage() {
  return (
    <Container size="lg" py="xl">
      <Stack gap="md">
        <Title order={1}>Ops</Title>
        <Text c="dimmed">
          Read-only view of the model registry, drift detection, A/B routing,
          retraining queue, and reliability summaries. Public — no auth — per
          decision [99]. Polishes deliberately minimal; the spine is the point.
        </Text>
        <Tabs defaultValue="registry">
          <Tabs.List>
            <Tabs.Tab value="registry">Registry</Tabs.Tab>
            <Tabs.Tab value="drift">Drift</Tabs.Tab>
            <Tabs.Tab value="routing">A/B routing</Tabs.Tab>
            <Tabs.Tab value="retrain">Retrain queue</Tabs.Tab>
            <Tabs.Tab value="reliability">Reliability</Tabs.Tab>
          </Tabs.List>
          <Tabs.Panel value="registry" pt="md">
            <RegistrySection />
          </Tabs.Panel>
          <Tabs.Panel value="drift" pt="md">
            <DriftSection />
          </Tabs.Panel>
          <Tabs.Panel value="routing" pt="md">
            <RoutingSection />
          </Tabs.Panel>
          <Tabs.Panel value="retrain" pt="md">
            <RetrainQueueSection />
          </Tabs.Panel>
          <Tabs.Panel value="reliability" pt="md">
            <ReliabilitySection />
          </Tabs.Panel>
        </Tabs>
      </Stack>
    </Container>
  );
}
