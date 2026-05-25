/**
 * Player autocomplete search box (leaf 4b.1).
 *
 * Debounces typed input by 200 ms before firing the TanStack Query — the dropdown
 * shows the previous result list while the new fetch is in flight (TanStack's
 * `keepPreviousData` semantics) so the UI doesn't flicker on each keystroke.
 *
 * Pure presentation + composition; the network call lives in `usePlayerSearch`.
 * The container page wires up navigation on selection.
 */
import { Autocomplete, Group, Text } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { useMemo, useState } from "react";

import { usePlayerSearch, type PlayerSearchResult } from "../../api/players";

export type PlayerSearchProps = {
  /** Called with the chosen row's id when the user picks a result. */
  onSelect: (player: PlayerSearchResult) => void;
  /** Placeholder text shown in the input. */
  placeholder?: string;
  /** Max number of results to render in the dropdown. Mirrors the server default. */
  limit?: number;
  /** Whether to autofocus the input on mount. */
  autoFocus?: boolean;
};

export function PlayerSearch({
  onSelect,
  placeholder = "Search players by name or ID…",
  limit = 10,
  autoFocus = false,
}: PlayerSearchProps) {
  const [raw, setRaw] = useState("");
  const [debounced] = useDebouncedValue(raw, 200);

  const query = usePlayerSearch(debounced, limit);

  /** Build dropdown items: "Name · POS" as label, id as value (string for Mantine). */
  const items = useMemo(
    () =>
      (query.data ?? []).map((p) => ({
        value: String(p.id),
        label: `${p.name} · ${p.primaryPosition}${p.active ? "" : " (retired)"}`,
        player: p,
      })),
    [query.data],
  );

  /** Mantine Autocomplete expects `data: string[] | { value, label }[]`. */
  const data = useMemo(
    () => items.map(({ value, label }) => ({ value, label })),
    [items],
  );

  const handleOptionSubmit = (value: string) => {
    const match = items.find((it) => it.value === value);
    if (match) {
      onSelect(match.player);
      setRaw("");
    }
  };

  const empty =
    debounced.trim().length >= 1 &&
    !query.isFetching &&
    (query.data?.length ?? 0) === 0;
  const errored = query.isError && debounced.trim().length >= 1;

  return (
    <div>
      <Autocomplete
        value={raw}
        onChange={setRaw}
        onOptionSubmit={handleOptionSubmit}
        data={data}
        placeholder={placeholder}
        autoFocus={autoFocus}
        limit={limit}
        comboboxProps={{ withinPortal: false }}
        rightSection={query.isFetching ? <Text size="xs">…</Text> : null}
      />
      {empty ? (
        <Group mt="xs" gap="xs">
          <Text size="sm" c="dimmed">
            No players match &quot;{debounced}&quot;.
          </Text>
        </Group>
      ) : null}
      {errored ? (
        <Group mt="xs" gap="xs">
          <Text size="sm" c="red">
            Search unavailable
            {query.error instanceof Error ? `: ${query.error.message}` : ""}.
          </Text>
        </Group>
      ) : null}
    </div>
  );
}
