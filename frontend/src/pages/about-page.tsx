/**
 * /about — long single-column report essay (Stage 3e, decision [133]).
 *
 * Completes the Stage 3 redesign sequence (home → parks → players →
 * games → ops → about). Replaces the prior off-identity editorial page
 * with a scouting-report colophon inside the locked <ReportSheet> shell:
 * cream + 1px navy border, max-width 1100, 32px padding, scarlet corner
 * stripes top-right.
 *
 * Composition order (top → bottom, inside the bordered sheet):
 *   1. <CornerStripes />              — scarlet 45° motif (decorative)
 *   2. <AboutHeader />                — eyebrow + two-line nameplate
 *                                       (`ABOUT` / `THE BULLPEN`)
 *                                       + byline strip + mono context
 *   3. <AboutFactsRibbon />           — navy strip, 4 artifact-count cells
 *                                       (133 DECISIONS · 7 ADRs · 3 MODELS
 *                                       · 4 CV FOLDS) — display only
 *   4. OPENING PITCH                  — 3 prose paragraphs
 *   5. THE STACK                      — 10-row LAYER / CHOICE / WHY table
 *   6. MODEL FLEET                    — 2 prose paragraphs + 4-row table
 *                                       (MODEL · VERSION · STATE · BACKBONE)
 *   7. OPERATIONAL DISCIPLINE         — KeyNotes with 5 numbered notes
 *   8. INTENTIONALLY NOT HERE         — 1 framing paragraph + 11 mono tags
 *   9. ROADMAP HONESTY                — 1 prose paragraph
 *  10. <AboutColophonFooter />        — navy strip, COLOPHON · SHA · BUILD
 *                                       on the left, github.com/<placeholder>
 *                                       on the right (plain text, not a link)
 *
 * Fixture-only (`about-fixtures.ts`); no API calls. The colophon is editorial
 * content that doesn't drift in v1.
 *
 * Constraints honored:
 *   - One <Title order={1}> only (the masthead h1).
 *   - No hex codes — every color via tokens or CSS-var utilities.
 *   - No anchor on the repo placeholder — plain text only (locked pick R2).
 *   - Reuses ReportSheet shell pattern, CornerStripes, SectionLabel, KeyNotes
 *     from existing primitives.
 *   - Body prose at editorial measure: IBM Plex Sans 16px × ~1.55
 *     line-height × ~62ch max-width.
 */

import { Stack } from "@mantine/core";

import { AboutColophonFooter } from "../components/about/about-colophon-footer";
import { AboutDiscipline } from "../components/about/about-discipline";
import { AboutFactsRibbon } from "../components/about/about-facts-ribbon";
import { AboutHeader } from "../components/about/about-header";
import { AboutModelFleet } from "../components/about/about-model-fleet";
import { AboutOpeningPitch } from "../components/about/about-opening-pitch";
import { AboutRejectedAlternatives } from "../components/about/about-rejected-alternatives";
import { AboutRoadmap } from "../components/about/about-roadmap";
import { AboutStackTable } from "../components/about/about-stack-table";
import { ReportSheet } from "../components/shared/report-sheet";
import { SectionLabel } from "../components/shared/section-label";
import {
  ABOUT_META,
  DISCIPLINE_NOTES,
  FACTS_RIBBON,
  FLEET_ROWS,
  MODEL_FLEET_PARAS,
  OPENING_PITCH_PARAS,
  REJECTED_PARA,
  REJECTED_TAGS,
  ROADMAP_PARA,
  STACK_ROWS,
} from "../data/about-fixtures";

import "./about/about.css";

export default function AboutPage() {
  return (
    <ReportSheet>
      <Stack gap={28}>
        <AboutHeader
          issueDate={ABOUT_META.issueDate}
          builtBy={ABOUT_META.builtBy}
          edition={ABOUT_META.edition}
          calendar={ABOUT_META.calendar}
          weeklyHours={ABOUT_META.weeklyHours}
        />

        <AboutFactsRibbon cells={FACTS_RIBBON} />

        <section aria-labelledby="about-opening-pitch-label">
          <div id="about-opening-pitch-label">
            <SectionLabel>Opening Pitch</SectionLabel>
          </div>
          <AboutOpeningPitch paragraphs={OPENING_PITCH_PARAS} />
        </section>

        <section aria-labelledby="about-stack-label">
          <div id="about-stack-label">
            <SectionLabel>The Stack</SectionLabel>
          </div>
          <AboutStackTable rows={STACK_ROWS} />
        </section>

        <section aria-labelledby="about-fleet-label">
          <div id="about-fleet-label">
            <SectionLabel>Model Fleet</SectionLabel>
          </div>
          <AboutModelFleet paragraphs={MODEL_FLEET_PARAS} rows={FLEET_ROWS} />
        </section>

        <section aria-labelledby="about-discipline-label">
          <div id="about-discipline-label">
            <SectionLabel>Operational Discipline</SectionLabel>
          </div>
          <AboutDiscipline notes={DISCIPLINE_NOTES} />
        </section>

        <section aria-labelledby="about-rejected-label">
          <div id="about-rejected-label">
            <SectionLabel>Intentionally Not Here</SectionLabel>
          </div>
          <AboutRejectedAlternatives
            paragraph={REJECTED_PARA}
            tags={REJECTED_TAGS}
          />
        </section>

        <section aria-labelledby="about-roadmap-label">
          <div id="about-roadmap-label">
            <SectionLabel>Roadmap Honesty</SectionLabel>
          </div>
          <AboutRoadmap paragraph={ROADMAP_PARA} />
        </section>

        <AboutColophonFooter
          buildSha={ABOUT_META.buildSha}
          buildDate={ABOUT_META.buildDate}
          repoPlaceholder={ABOUT_META.repoPlaceholder}
        />
      </Stack>
    </ReportSheet>
  );
}
