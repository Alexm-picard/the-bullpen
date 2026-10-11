/**
 * N3 - real build stamp. `__BUILD_SHA__` / `__BUILD_DATE__` are replaced at build/test time by
 * Vite's `define` (see vite.config.ts), sourced from git (or VERCEL_GIT_COMMIT_SHA on Vercel). This
 * is the single source for the footer build stamp, replacing the hand-frozen fixture SHAs and the
 * "build live" BUILD_FALLBACK placeholders the page footers used to show.
 */
declare const __BUILD_SHA__: string;
declare const __BUILD_DATE__: string;
declare const __DECISION_COUNT__: number | null;
declare const __ADR_COUNT__: number | null;

export const BUILD_SHA: string = __BUILD_SHA__;
export const BUILD_DATE: string = __BUILD_DATE__;

/** Numbered entries in docs/decisions.md at build time; null when the build could not read docs/. */
export const DECISION_COUNT: number | null = __DECISION_COUNT__;
/** ADR files in docs/adr at build time; null when the build could not read docs/. */
export const ADR_COUNT: number | null = __ADR_COUNT__;
