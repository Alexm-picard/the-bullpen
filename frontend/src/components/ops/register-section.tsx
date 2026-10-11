/**
 * The /ops register's frame (SPEC-ops sections 2 and 6.1): a numbered section with a provenance
 * margin, and the source stamp that is every section's honesty label.
 *
 * The provenance list renders twice from ONE definition: in the margin (desktop) and inside a
 * "Where this comes from" disclosure (phone). CSS shows exactly one; the hidden copy is
 * display:none, so assistive tech never hears it twice.
 */
import { Fragment, useState, type ReactNode } from "react";

import type { SourceState } from "../../lib/ops-status";

import { readTime } from "./ops-format";

export type StampState = SourceState | "static";

/** The state is the word, never a colour. */
export function SourceStamp({
  state,
  source,
  updatedAt = 0,
}: {
  state: StampState;
  source: string;
  updatedAt?: number;
}) {
  const at = readTime(updatedAt);
  let word: string;
  let tail: string;
  switch (state) {
    case "loading":
      word = "Loading.";
      tail = source;
      break;
    case "live":
      word = "Live.";
      tail = at ? `${source}, read ${at}` : source;
      break;
    case "empty":
      word = "Live, empty.";
      tail = at ? `${source}, read ${at}` : source;
      break;
    case "stale":
      word = "Live, stale.";
      tail = `${source}, last read ${at}; the latest refresh failed`;
      break;
    case "error":
      word = "Showcase data.";
      tail = "the backend did not answer; nothing below is a reading";
      break;
    case "static":
      word = "Static.";
      tail = source;
      break;
  }
  return (
    <p className="op-src" data-state={state}>
      <b>{word}</b> {tail}
    </p>
  );
}

export type ProvenanceItem = { term: string; detail: ReactNode };

function ProvenanceList({ items }: { items: readonly ProvenanceItem[] }) {
  return (
    <dl className="op-prov">
      {items.map((it) => (
        <div key={it.term}>
          <dt>{it.term}</dt>
          <dd>{it.detail}</dd>
        </div>
      ))}
    </dl>
  );
}

export function RegisterSection({
  n,
  id,
  title,
  stamp,
  provenance,
  children,
}: {
  n: number;
  id: string;
  title: string;
  stamp: ReactNode;
  provenance?: readonly ProvenanceItem[];
  children: ReactNode;
}) {
  const headingId = `${id}-h`;
  return (
    <section className="op-sec" id={id} aria-labelledby={headingId}>
      <header className="op-sec__margin">
        <span className="op-sec__no" aria-hidden="true">
          {n}
        </span>
        <h2 className="ed-h2" id={headingId}>
          {title}
        </h2>
        {stamp}
        {provenance && provenance.length > 0 ? (
          <>
            <ProvenanceList items={provenance} />
            <details className="ed-disclosure op-prov-disclosure">
              <summary>Where this comes from</summary>
              <ProvenanceList items={provenance} />
            </details>
          </>
        ) : null}
      </header>
      <div className="op-sec__body">{children}</div>
    </section>
  );
}

/**
 * A polled value that changed since first render cross-fades once (150 ms, opacity only; off
 * under reduced motion). Unchanged values never animate: the key only changes with the value.
 */
export function Fresh({ value }: { value: string }) {
  const [first] = useState(value);
  const changed = value !== first;
  return (
    <span key={value} className={changed ? "op-fresh" : undefined}>
      {value}
    </span>
  );
}

/** Break long registry names at underscores rather than mid-word on a phone. */
export function Breakable({ name }: { name: string }) {
  const parts = name.split("_");
  return (
    <>
      {parts.map((p, i) => (
        <Fragment key={i}>
          {p}
          {i < parts.length - 1 ? (
            <>
              _<wbr />
            </>
          ) : null}
        </Fragment>
      ))}
    </>
  );
}
