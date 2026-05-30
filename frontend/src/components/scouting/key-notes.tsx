/**
 * <KeyNotes> — the editorial panel that summarizes the matchup in prose.
 *
 * Visual: a sheet panel with a navy lower-third header bar reading "KEY NOTES",
 * followed by numbered prose paragraphs in IBM Plex Sans. The numbers are mono
 * scarlet so each note reads as a discrete observation, scouting-packet style.
 *
 * Accepts a string[] of notes (1–4). Notes longer than ~3 are still rendered
 * but the visual cadence will tighten — the page caps at 3 by convention.
 */

import { radii, colors, typography } from "../../design/tokens";

export type KeyNotesProps = {
  notes: string[];
};

export function KeyNotes({ notes }: KeyNotesProps) {
  return (
    <section
      style={{
        backgroundColor: colors.bgSheet,
        border: `1px solid ${colors.bgEmphasis}`,
        borderRadius: radii.sm,
      }}
      aria-labelledby="key-notes-header"
    >
      <div
        id="key-notes-header"
        style={{
          backgroundColor: colors.navy,
          color: colors.textOnNavy,
          fontFamily: typography.fonts.display,
          fontSize: typography.scale[1], // 14
          fontWeight: typography.weights.bold,
          textTransform: "uppercase",
          letterSpacing: "0.06em",
          padding: "8px 16px",
        }}
      >
        Key Notes
      </div>
      <ol
        style={{
          listStyle: "none",
          margin: 0,
          padding: 16,
          display: "flex",
          flexDirection: "column",
          gap: 12,
        }}
      >
        {notes.map((note, i) => (
          <li
            key={i}
            style={{
              display: "grid",
              gridTemplateColumns: "28px 1fr",
              gap: 8,
              alignItems: "baseline",
              fontFamily: typography.fonts.body,
              fontSize: typography.scale[1], // 14
              lineHeight: typography.lineHeights.body,
              color: colors.textDefault,
            }}
          >
            <span
              aria-hidden="true"
              style={{
                fontFamily: typography.fonts.mono,
                fontSize: typography.scale[1],
                fontWeight: typography.weights.bold,
                color: colors.scarlet,
              }}
            >
              {String(i + 1).padStart(2, "0")}
            </span>
            <span>{note}</span>
          </li>
        ))}
      </ol>
    </section>
  );
}
