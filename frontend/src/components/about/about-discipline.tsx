/**
 * <AboutDiscipline> — five numbered prose paragraphs of operational discipline.
 *
 * Reuses the existing <KeyNotes> primitive from scouting/. KeyNotes accepts a
 * string[] of notes and renders each with a zero-padded scarlet mono number
 * and an IBM Plex Sans note body. The numbered list semantics + the "Key
 * Notes" header bar pattern fit the colophon's "five disciplines" section.
 *
 * Notes here are full sentences (not bullet snippets) — see DISCIPLINE_NOTES
 * in about-fixtures.ts. This is a thin adapter so the page can stay flat.
 */

import { broadcastKeyNotesPalette } from "../broadcast/palettes";
import { KeyNotes } from "../scouting/key-notes";

export type AboutDisciplineProps = {
  notes: string[];
};

export function AboutDiscipline({ notes }: AboutDisciplineProps) {
  return <KeyNotes notes={notes} palette={broadcastKeyNotesPalette} />;
}
