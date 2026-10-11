/**
 * <PlayerSearch> - the /players lede's search field ([195] editorial skin, SPEC-players 2.1).
 *
 * A ruled serif field with a VISIBLE label ("Name or Statcast ID": the backend treats a numeric
 * query as a Statcast ID prefix, so that is exactly what the field accepts). Built on Mantine's
 * Autocomplete for the combobox semantics and keyboard model; the editorial look comes from the
 * .pl-search__* classes in design/editorial.css.
 *
 * Typed input is debounced 200 ms before the TanStack query fires. The previous result list stays
 * up (dimmed) while the next fetch is in flight, so the list never flickers per keystroke, and
 * keyboard movement through it is never animated (high-frequency, keyboard-initiated). The server
 * already filtered the rows, so the client-side label filter is disabled - otherwise a Statcast ID
 * query would hide its own matches (the ID is not in the label).
 */
import { Autocomplete, VisuallyHidden } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";

import { usePlayerSearch, type PlayerSearchResult } from "../../api/players";

export type PlayerSearchProps = {
  /** Called with the chosen row when the user picks a result. */
  onSelect: (player: PlayerSearchResult) => void;
  /** Max number of results to render. Mirrors the server default. */
  limit?: number;
  /** Whether to autofocus the input on mount. */
  autoFocus?: boolean;
};

export const SEARCH_LABEL = "Name or Statcast ID";

/** Bold the first case-insensitive occurrence of `q` in `name` (weight, never colour). */
function highlight(name: string, q: string): ReactNode {
  const needle = q.trim().toLowerCase();
  const at = needle ? name.toLowerCase().indexOf(needle) : -1;
  if (at < 0) return name;
  return (
    <>
      {name.slice(0, at)}
      <mark>{name.slice(at, at + needle.length)}</mark>
      {name.slice(at + needle.length)}
    </>
  );
}

function isTypingTarget(el: EventTarget | null): boolean {
  if (!(el instanceof HTMLElement)) return false;
  return (
    el.isContentEditable ||
    el.tagName === "INPUT" ||
    el.tagName === "TEXTAREA" ||
    el.tagName === "SELECT"
  );
}

export function PlayerSearch({
  onSelect,
  limit = 10,
  autoFocus = false,
}: PlayerSearchProps) {
  const [raw, setRaw] = useState("");
  const [debounced] = useDebouncedValue(raw, 200);
  const inputRef = useRef<HTMLInputElement>(null);
  const query = usePlayerSearch(debounced, limit);

  // "/" focuses the field from anywhere on the page, unless the reader is already typing.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "/" || e.metaKey || e.ctrlKey || e.altKey) return;
      if (isTypingTarget(e.target)) return;
      e.preventDefault();
      inputRef.current?.focus();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []);

  const byId = useMemo(
    () => new Map((query.data ?? []).map((p) => [String(p.id), p])),
    [query.data],
  );
  const data = useMemo(
    () =>
      (query.data ?? []).map((p) => ({ value: String(p.id), label: p.name })),
    [query.data],
  );

  const handleOptionSubmit = (value: string) => {
    const match = byId.get(value);
    if (match) {
      onSelect(match);
      setRaw("");
    }
  };

  const typed = debounced.trim().length >= 1;
  // Only a SUCCESSFUL empty answer is "no match"; a failed fetch is not a statement about the data.
  const empty =
    typed &&
    query.isSuccess &&
    !query.isPlaceholderData &&
    query.data.length === 0;
  const errored = typed && query.isError;
  const found =
    typed && query.isSuccess && !query.isPlaceholderData
      ? query.data.length
      : null;

  return (
    <div className="pl-search">
      <Autocomplete
        ref={inputRef}
        label={SEARCH_LABEL}
        value={raw}
        onChange={setRaw}
        onOptionSubmit={handleOptionSubmit}
        data={data}
        filter={({ options }) => options}
        placeholder="Skubal, or 669373"
        autoFocus={autoFocus}
        limit={limit}
        classNames={{
          label: "pl-search__label",
          input: "pl-search__input",
          dropdown: "pl-search__dropdown",
          option: "pl-search__option",
        }}
        rightSection={
          <span className="pl-kbd" aria-hidden="true">
            /
          </span>
        }
        rightSectionPointerEvents="none"
        comboboxProps={{
          withinPortal: false,
          // Anchored to the field it came from; quick, and only on open/close.
          transitionProps: { transition: "pop-top-left", duration: 150 },
        }}
        styles={{
          // Previous results stay up while the next fetch is in flight; dim them so a stale list
          // never reads as the answer to the new query.
          dropdown: { opacity: query.isPlaceholderData ? 0.6 : 1 },
        }}
        renderOption={({ option }) => {
          const p = byId.get(option.value);
          if (!p) return option.value;
          return (
            <>
              <span className="pl-search__name">
                {highlight(p.name, debounced)}
              </span>
              <span>{p.primaryPosition.trim() || "-"}</span>
              <span className={p.active ? undefined : "pl-faint"}>
                {p.active ? p.team || "-" : "inactive"}
              </span>
            </>
          );
        }}
      />
      <VisuallyHidden aria-live="polite">
        {found == null
          ? ""
          : `${found} ${found === 1 ? "player" : "players"} found`}
      </VisuallyHidden>
      {empty ? (
        <p className="pl-search__msg">
          No player matches &ldquo;{debounced.trim()}&rdquo;.
        </p>
      ) : null}
      {errored ? (
        <p className="pl-search__msg">Search is unavailable right now.</p>
      ) : null}
      <p className="pl-hint">
        Arrow keys move through matches, Enter opens the file.
      </p>
    </div>
  );
}
