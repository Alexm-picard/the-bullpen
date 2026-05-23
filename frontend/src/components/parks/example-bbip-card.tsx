import { Badge, Button, Card, Group, Stack, Text } from "@mantine/core";
import {
  usePredictBattedBall,
  type BattedBallRequest,
} from "../../api/predict";

export type ExampleBbip = BattedBallRequest & {
  id: string;
  label: string;
  blurb: string;
};

export function ExampleBbipCard({ example }: { example: ExampleBbip }) {
  const mutation = usePredictBattedBall();
  const { launchSpeedMph, launchAngleDeg, releaseSpeedMph, parkId, stand } =
    example;

  return (
    <Card
      shadow="sm"
      padding="md"
      radius="md"
      withBorder
      data-testid={`example-bbip-${example.id}`}
    >
      <Stack gap="xs">
        <Group justify="space-between" align="baseline">
          <Text fw={600}>{example.label}</Text>
          <Badge variant="light" color="gray">
            {parkId}
          </Badge>
        </Group>
        <Text size="sm" c="dimmed">
          {example.blurb}
        </Text>
        <Group gap="xs" wrap="wrap">
          <Badge variant="outline">{launchSpeedMph.toFixed(1)} mph EV</Badge>
          <Badge variant="outline">{launchAngleDeg}° LA</Badge>
          <Badge variant="outline">{releaseSpeedMph} mph pitch</Badge>
          <Badge variant="outline">stand {stand}</Badge>
        </Group>
        <Button
          variant="filled"
          color="dark"
          onClick={() =>
            mutation.mutate({
              launchSpeedMph,
              launchAngleDeg,
              releaseSpeedMph,
              parkId,
              stand,
            })
          }
          loading={mutation.isPending}
          data-testid={`predict-${example.id}`}
        >
          {mutation.data ? "Predict again" : "Predict"}
        </Button>
        {mutation.data && (
          <Group justify="space-between" data-testid={`result-${example.id}`}>
            <Text size="sm">
              <span style={{ fontWeight: 600 }}>p(HR)</span>{" "}
              {(mutation.data.probHr * 100).toFixed(1)}%
            </Text>
            <Text size="xs" c="dimmed">
              {mutation.data.modelName} {mutation.data.modelVersion} ·{" "}
              {Math.round(mutation.data.latencyMicros / 1000)} ms
            </Text>
          </Group>
        )}
        {mutation.error && (
          <Text size="sm" c="red" data-testid={`error-${example.id}`}>
            {mutation.error.message}
          </Text>
        )}
      </Stack>
    </Card>
  );
}
