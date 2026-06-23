/**
 * N3 - real build stamp. `__BUILD_SHA__` / `__BUILD_DATE__` are replaced at build/test time by
 * Vite's `define` (see vite.config.ts), sourced from git (or VERCEL_GIT_COMMIT_SHA on Vercel). This
 * is the single source for the footer build stamp, replacing the hand-frozen fixture SHAs and the
 * "build live" BUILD_FALLBACK placeholders the page footers used to show.
 */
declare const __BUILD_SHA__: string;
declare const __BUILD_DATE__: string;

export const BUILD_SHA: string = __BUILD_SHA__;
export const BUILD_DATE: string = __BUILD_DATE__;
