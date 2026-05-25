/**
 * Ops dashboard — Reliability section (leaf 4e.5).
 *
 * For each registered model, parses the eval_metrics JSON of the latest
 * version and surfaces the headline numbers (Brier / ECE / log-loss / accuracy
 * — whatever the model recorded). The detailed per-player reliability diagram
 * is the existing 4b.3 component; this section is the aggregate roll-up.
 */
import { Code, Group, Stack, Table, Text, Title } from "@mantine/core";
import { useMemo } from "react";

import { useCalibrationSummary } from "../../api/ops";

type ParsedMetrics = Record<string, unknown>;

export function ReliabilitySection() {
  const { data, isLoading, isError, error } = useCalibrationSummary();

  if (isLoading) {
    return (
      <Text c="dimmed" size="sm">
        Loading calibration summary…
      </Text>
    );
  }
  if (isError) {
    return (
      <Text c="red" size="sm">
        Could not load calibration summary
        {error instanceof Error ? `: ${error.message}` : ""}.
      </Text>
    );
  }
  if (!data || Object.keys(data).length === 0) {
    return (
      <Text c="dimmed" size="sm">
        No registered models yet. Calibration summaries materialise as soon as
        the first model registers with eval_metrics (Phase 2a.7+).
      </Text>
    );
  }

  return (
    <Stack gap="lg">
      {Object.entries(data).map(([modelName, json]) => (
        <ModelCalibrationRow
          key={modelName}
          modelName={modelName}
          json={json}
        />
      ))}
    </Stack>
  );
}

function ModelCalibrationRow({
  modelName,
  json,
}: {
  modelName: string;
  json: string;
}) {
  const parsed = useMemo(() => safeJson(json), [json]);
  const headline = useMemo(() => extractHeadline(parsed), [parsed]);

  return (
    <Stack gap={4}>
      <Group gap="md" align="flex-end">
        <Title order={4} style={{ margin: 0 }}>
          {modelName}
        </Title>
        {headline.length === 0 ? (
          <Text size="sm" c="dimmed">
            no eval_metrics on latest version
          </Text>
        ) : null}
      </Group>
      {headline.length > 0 ? (
        <Table>
          <Table.Tbody>
            {headline.map(({ key, value }) => (
              <Table.Tr key={key}>
                <Table.Td>
                  <Text size="sm" c="dimmed">
                    {key}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Text size="sm" ff="monospace">
                    {value}
                  </Text>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      ) : null}
      {parsed != null ? (
        <details>
          <summary>
            <Text component="span" size="xs" c="dimmed" tt="uppercase">
              raw eval_metrics
            </Text>
          </summary>
          <Code block>{JSON.stringify(parsed, null, 2)}</Code>
        </details>
      ) : null}
    </Stack>
  );
}

const HEADLINE_KEYS = [
  "brier",
  "ece",
  "log_loss",
  "accuracy",
  "auc",
  "n_eval",
] as const;

function extractHeadline(
  parsed: ParsedMetrics | string | null,
): { key: string; value: string }[] {
  if (parsed == null || typeof parsed === "string") return [];
  const out: { key: string; value: string }[] = [];
  for (const key of HEADLINE_KEYS) {
    if (key in parsed) {
      const v = parsed[key];
      if (typeof v === "number") {
        out.push({ key, value: v.toFixed(4) });
      } else if (v != null) {
        out.push({ key, value: String(v) });
      }
    }
  }
  return out;
}

function safeJson(text: string): ParsedMetrics | string | null {
  if (!text) return null;
  try {
    const parsed = JSON.parse(text);
    if (parsed && typeof parsed === "object") return parsed as ParsedMetrics;
    return String(parsed);
  } catch {
    return text;
  }
}
