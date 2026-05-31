/**
 * /admin/routing (B7) — operator override for the A/B router, wrapping the auth-gated
 * POST/DELETE /v1/admin/routing/* endpoints (RoutingAdminController) behind HTTP Basic.
 *
 * Deliberately utilitarian, NOT the scouting-report identity: this is an internal operator
 * tool, unlisted in the public nav and reached by URL. Credentials live only in this
 * component's in-memory state (never persisted) and ride each request as an Authorization
 * header. Current routing is read from the public GET /v1/ops/routing (useRouting); a
 * successful write invalidates that query so the displayed state refreshes.
 *
 * Friction by design (mirrors the backend): setting a challenger resets traffic to 0, and
 * moving the slider is a separate explicit action — no accidental one-click cutover.
 */
import {
  Alert,
  Badge,
  Button,
  Card,
  Container,
  Group,
  NumberInput,
  PasswordInput,
  SegmentedControl,
  Select,
  Stack,
  Text,
  TextInput,
  Title,
} from "@mantine/core";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";

import {
  type AdminCreds,
  clearChallenger,
  setChallenger,
  setRoutingMode,
  setTrafficPct,
} from "../api/admin";
import { useRouting } from "../api/ops";

type Banner = { ok: boolean; text: string };

export default function AdminRoutingPage() {
  const [creds, setCreds] = useState<AdminCreds | null>(null);
  const [user, setUser] = useState("");
  const [password, setPassword] = useState("");

  const routing = useRouting();
  const [selected, setSelected] = useState<string | null>(null);
  const [reason, setReason] = useState("");
  const [mode, setMode] = useState<"SHADOW" | "AB">("SHADOW");
  const [pct, setPct] = useState<number | string>(0);
  const [challengerId, setChallengerId] = useState<number | string>("");
  const [banner, setBanner] = useState<Banner | null>(null);

  const qc = useQueryClient();
  const current = routing.data?.find((r) => r.modelName === selected) ?? null;

  function onSelectModel(modelName: string | null) {
    setSelected(modelName);
    setBanner(null);
    const row = routing.data?.find((r) => r.modelName === modelName);
    if (row) {
      setMode(row.mode === "AB" ? "AB" : "SHADOW");
      setPct(row.challengerTrafficPct);
      setChallengerId(row.challengerVersionId ?? "");
    }
  }

  // Plain (non-hook) factory for the shared success/error banner handlers, so the
  // useMutation calls below stay at the component's top level (rules-of-hooks).
  function bannerHandlers(label: string) {
    return {
      onSuccess: () => {
        void qc.invalidateQueries({ queryKey: ["ops", "routing"] });
        setBanner({ ok: true, text: `${label} applied.` });
      },
      onError: (e: unknown) =>
        setBanner({ ok: false, text: (e as Error).message }),
    };
  }

  const modeMut = useMutation({
    mutationFn: () => setRoutingMode(creds!, selected!, mode, reason),
    ...bannerHandlers("Mode"),
  });
  const trafficMut = useMutation({
    mutationFn: () => setTrafficPct(creds!, selected!, Number(pct), reason),
    ...bannerHandlers("Traffic %"),
  });
  const challengerMut = useMutation({
    mutationFn: () =>
      setChallenger(creds!, selected!, Number(challengerId), reason),
    ...bannerHandlers("Challenger"),
  });
  const clearMut = useMutation({
    mutationFn: () => clearChallenger(creds!, selected!),
    ...bannerHandlers("Clear challenger"),
  });

  const anyPending =
    modeMut.isPending ||
    trafficMut.isPending ||
    challengerMut.isPending ||
    clearMut.isPending;
  const reasonMissing = reason.trim().length === 0;
  const noModel = selected == null;

  if (!creds) {
    return (
      <Container size="xs" py="xl">
        <Title order={1} mb="xs">
          Routing Override
        </Title>
        <Text c="dimmed" size="sm" mb="lg">
          Admin · enter the HTTP Basic credentials for <code>/v1/admin</code>.
        </Text>
        <Card withBorder padding="lg">
          <Stack>
            <TextInput
              label="User"
              value={user}
              onChange={(e) => setUser(e.currentTarget.value)}
              autoComplete="username"
            />
            <PasswordInput
              label="Password"
              value={password}
              onChange={(e) => setPassword(e.currentTarget.value)}
              autoComplete="current-password"
            />
            <Button
              disabled={!user || !password}
              onClick={() => setCreds({ user, password })}
            >
              Connect
            </Button>
            <Text c="dimmed" size="xs">
              Credentials are held in memory for this tab only and sent as an
              Authorization header — never stored.
            </Text>
          </Stack>
        </Card>
      </Container>
    );
  }

  return (
    <Container size="sm" py="xl">
      <Group justify="space-between" mb="xs">
        <Title order={1}>Routing Override</Title>
        <Button variant="subtle" size="xs" onClick={() => setCreds(null)}>
          Disconnect
        </Button>
      </Group>
      <Text c="dimmed" size="sm" mb="lg">
        Admin · <code>{user}</code> · A/B router for the registered models.
      </Text>

      {banner && (
        <Alert
          color={banner.ok ? "green" : "red"}
          mb="md"
          withCloseButton
          onClose={() => setBanner(null)}
        >
          {banner.text}
        </Alert>
      )}

      {routing.isError && (
        <Alert color="red" mb="md">
          Could not load current routing.
        </Alert>
      )}

      <Select
        label="Model"
        placeholder={routing.isLoading ? "Loading…" : "Select a routed model"}
        data={(routing.data ?? []).map((r) => r.modelName)}
        value={selected}
        onChange={onSelectModel}
        mb="lg"
      />

      {current && (
        <Card withBorder padding="lg">
          <Stack>
            <Group gap="xs">
              <Badge color={current.mode === "AB" ? "green" : "gray"}>
                {current.mode}
              </Badge>
              <Text size="sm" c="dimmed">
                champion v{current.championVersionId} · challenger{" "}
                {current.challengerVersionId ?? "—"} · traffic{" "}
                {current.challengerTrafficPct}%
              </Text>
            </Group>

            <TextInput
              label="Reason (audit log)"
              placeholder="why this change"
              value={reason}
              onChange={(e) => setReason(e.currentTarget.value)}
              required
            />

            <Group align="flex-end" grow>
              <div>
                <Text size="sm" fw={600} mb={4}>
                  Mode
                </Text>
                <SegmentedControl
                  fullWidth
                  value={mode}
                  onChange={(v) => setMode(v as "SHADOW" | "AB")}
                  data={["SHADOW", "AB"]}
                />
              </div>
              <Button
                loading={modeMut.isPending}
                disabled={anyPending || reasonMissing || noModel}
                onClick={() => modeMut.mutate()}
              >
                Apply mode
              </Button>
            </Group>

            <Group align="flex-end" grow>
              <NumberInput
                label="Challenger traffic %"
                min={0}
                max={100}
                value={pct}
                onChange={setPct}
              />
              <Button
                loading={trafficMut.isPending}
                disabled={anyPending || reasonMissing || noModel}
                onClick={() => trafficMut.mutate()}
              >
                Apply traffic
              </Button>
            </Group>

            <Group align="flex-end" grow>
              <NumberInput
                label="Challenger version id"
                min={1}
                value={challengerId}
                onChange={setChallengerId}
              />
              <Button
                loading={challengerMut.isPending}
                disabled={
                  anyPending || reasonMissing || noModel || !challengerId
                }
                onClick={() => challengerMut.mutate()}
              >
                Set challenger
              </Button>
            </Group>

            <Button
              variant="light"
              color="red"
              loading={clearMut.isPending}
              disabled={anyPending || noModel}
              onClick={() => clearMut.mutate()}
            >
              Clear challenger
            </Button>
          </Stack>
        </Card>
      )}
    </Container>
  );
}
