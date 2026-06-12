/**
 * <KeyNotes> — the editorial panel that summarizes the matchup in prose.
 *
 * Visual: a sheet panel with a lower-third header bar reading "KEY NOTES",
 * followed by numbered prose paragraphs. The numbers are mono accent-colored
 * so each note reads as a discrete observation, scouting-packet style.
 *
 * Identity-parameterized ([160] migration, same pattern as StatTable):
 * broadcast screens pass `broadcastKeyNotesPalette`; legacy consumers
 * (about, parks) keep the built-in default until their own migration PRs.
 *
 * Accepts a string[] of notes (1–4). Notes longer than ~3 are still rendered
 * but the visual cadence will tighten — the page caps at 3 by convention.
 */

import { radii, colors, typography } from "../../design/tokens";

export type KeyNotesPalette = {
  surface: string;
  border: string;
  headerBg: string;
  headerText: string;
  headerFontStyle: "normal" | "italic";
  noteText: string;
  numberAccent: string;
  displayFont: string;
  bodyFont: string;
  monoFont: string;
  /** Corner radius; broadcast panels are square. */
  radius: number;
};

const LEGACY_PALETTE: KeyNotesPalette = {
  surface: colors.bgSheet,
  border: colors.bgEmphasis,
  headerBg: colors.navy,
  headerText: colors.textOnNavy,
  headerFontStyle: "normal",
  noteText: colors.textDefault,
  numberAccent: colors.scarlet,
  displayFont: typography.fonts.display,
  bodyFont: typography.fonts.body,
  monoFont: typography.fonts.mono,
  radius: radii.sm,
};

export type KeyNotesProps = {
  notes: string[];
  /** Identity palette; defaults to the legacy scouting-report identity. */
  palette?: KeyNotesPalette;
};

export function KeyNotes({ notes, palette = LEGACY_PALETTE }: KeyNotesProps) {
  return (
    <section
      style={{
        backgroundColor: palette.surface,
        border: `1px solid ${palette.border}`,
        borderRadius: palette.radius,
      }}
      aria-labelledby="key-notes-header"
    >
      <div
        id="key-notes-header"
        style={{
          backgroundColor: palette.headerBg,
          color: palette.headerText,
          fontFamily: palette.displayFont,
          ...(palette.headerFontStyle === "italic"
            ? { fontStyle: "italic" as const }
            : {}),
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
              fontFamily: palette.bodyFont,
              fontSize: typography.scale[1], // 14
              lineHeight: typography.lineHeights.body,
              color: palette.noteText,
            }}
          >
            <span
              aria-hidden="true"
              style={{
                fontFamily: palette.monoFont,
                fontSize: typography.scale[1],
                fontWeight: typography.weights.bold,
                color: palette.numberAccent,
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
