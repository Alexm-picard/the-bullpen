/**
 * The postmortem shelf behind the home page's "From the desk" column (SPEC-home §6, rotating to the
 * latest write-up in docs/postmortems/). The docs are not served by the SPA, so each entry links to
 * the file on GitHub.
 *
 * `postmortems.test.ts` lists docs/postmortems/ on disk and fails when a file there is missing from
 * this list, so adding a postmortem without adding it here is a red test, not a stale page.
 */

export type PostmortemEntry = {
  /** File name under docs/postmortems/. */
  file: string;
  /** The date the write-up is about (its event or drill date), ISO yyyy-mm-dd. */
  date: string;
  /** Headline as the page prints it. */
  title: string;
  /** One sentence, plain words. */
  dek: string;
  /** Honesty label: a drill is never presented as a real incident. */
  kind: "postmortem" | "incident" | "drill" | "triage";
};

const REPO_BLOB = "https://github.com/Alexm-picard/the-bullpen/blob/main";

export const POSTMORTEMS: readonly PostmortemEntry[] = [
  {
    file: "2026-08-03_pitcher-form-silent-staleness.md",
    date: "2026-08-03",
    title: "Two months of silent staleness",
    dek: "How designed detection caught a pitcher-form feature that had stopped updating on the live pitch path, and what changed.",
    kind: "postmortem",
  },
  {
    file: "2026-07-16_first-organic-psi-triage.md",
    date: "2026-07-16",
    title: "The first organic drift notices",
    dek: "Two feature-drift notices on a tiny All-Star-break sample, triaged to a small-sample false positive.",
    kind: "triage",
  },
  {
    file: "2026-07-16_induced-drift-drill.md",
    date: "2026-07-16",
    title: "An induced-drift drill on the live path",
    dek: "A deliberately injected shift, tagged and excludable, run against production to prove detect-to-alert end to end.",
    kind: "drill",
  },
  {
    file: "2026-07-15_c31-retrain-saga.md",
    date: "2026-07-15",
    title: "Sixteen attempts to one automated retrain",
    dek: "What it took for the retraining control plane to register a candidate on real data, unattended.",
    kind: "postmortem",
  },
  {
    file: "incident-2026-06-07-first-champion-promotion-500.md",
    date: "2026-06-07",
    title: "The first champion promotion",
    dek: "Promoting the first batted-ball champion broke the all-parks endpoint; four layered fixes and a clean recovery.",
    kind: "incident",
  },
  {
    file: "drill-2026-05-30-induced-battedball-drift.md",
    date: "2026-05-30",
    title: "Induced batted-ball drift",
    dek: "A drift-induction drill that proved the detector had teeth before the season's first real event.",
    kind: "drill",
  },
];

/** The newest write-up by date (file name breaks ties, so the pick is stable). */
export function latestPostmortem(
  entries: readonly PostmortemEntry[] = POSTMORTEMS,
): PostmortemEntry | null {
  return (
    [...entries].sort(
      (a, b) => b.date.localeCompare(a.date) || b.file.localeCompare(a.file),
    )[0] ?? null
  );
}

export function postmortemHref(entry: PostmortemEntry): string {
  return `${REPO_BLOB}/docs/postmortems/${entry.file}`;
}
