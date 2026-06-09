/**
 * /about -- long single-column report essay (Stage 3e, decision [133]).
 *
 * Composition order (top -> bottom, inside the bordered sheet):
 *   1. <AboutHeader />                -- eyebrow + two-line nameplate + byline
 *   2. <AboutFactsRibbon />           -- navy strip, 4 artifact-count cells
 *   3. OPENING PITCH                  -- 3 prose paragraphs
 *   4. THE STACK                      -- 10-row LAYER / CHOICE / WHY table
 *   5. MODEL FLEET                    -- 2 prose paragraphs + fleet table
 *   6. OPERATIONAL DISCIPLINE         -- KeyNotes with 5 numbered notes
 *   7. INTENTIONALLY NOT HERE         -- 1 framing paragraph + 11 mono tags
 *   8. ROADMAP HONESTY                -- 1 prose paragraph
 *   9. <AboutColophonFooter />        -- navy strip colophon footer
 *
 * Data sourcing (W7):
 *   - Model Fleet table: LIVE via useAllRegistryRows. Falls back to
 *     FLEET_ROWS fixture when backend is unreachable. Backbone is derived
 *     from model name since the registry does not store it -- the mapping
 *     is fixed by the project's locked model choices. Captioned honestly.
 *   - All other sections: editorial fixture content (ABOUT_META, FACTS_RIBBON,
 *     STACK_ROWS, prose, DISCIPLINE_NOTES, REJECTED_*, ROADMAP_PARA). These
 *     are colophon content that does not drift with the backend.
 *
 * Constraints honored:
 *   - One <Title order={1}> only (the masthead h1).
 *   - No hex codes -- every color via tokens.
 *   - TanStack Query for live fleet data; no useEffect for server state.
 *   - No anchor on the repo placeholder (plain text only, locked pick R2).
 */

import { Stack, Text } from "@mantine/core";

import { useAllRegistryRows } from "../api/ops";
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
import type { FleetRow, FleetRowState } from "../data/about-fixtures";
import type { ModelVersion } from "../api/ops";
import { colors } from "../design/tokens";

import "./about/about.css";

// ── Helpers ───────────────────────────────────────────────────────────────────

/**
 * Derive the backbone description from the model name. The project's model
 * choices are locked; the registry does not store backbone metadata.
 */
function backboneFor(modelName: string): string {
  const name = modelName.toLowerCase();
  if (name.includes("lr_baseline") || name.includes("lr-baseline")) {
    return "Logistic regression";
  }
  if (name.includes("pitch_outcome") || name.includes("pitch-outcome")) {
    return "LightGBM multinomial";
  }
  if (name.includes("batted_ball") || name.includes("batted-ball")) {
    return "Shared MLP + 30 park heads";
  }
  return "n/a";
}

/**
 * Map live registry rows to the FleetRow shape AboutModelFleet renders.
 * CHAMPION stage -> LIVE; everything else -> SHADOW. ARCHIVED rows are
 * filtered out (same convention as ops-page).
 */
function registryToFleetRows(versions: ModelVersion[]): FleetRow[] {
  return versions
    .filter((v) => v.stage.toUpperCase() !== "ARCHIVED")
    .map((v) => {
      const state: FleetRowState =
        v.stage.toUpperCase() === "CHAMPION" ? "LIVE" : "SHADOW";
      return {
        model: v.modelName,
        version: v.version,
        state,
        backbone: backboneFor(v.modelName),
      };
    });
}

// ── Page component ────────────────────────────────────────────────────────────

export default function AboutPage() {
  // Model fleet: LIVE when the registry returns at least one row.
  const registry = useAllRegistryRows();

  const liveFleet =
    registry.data && registry.data.length > 0
      ? registryToFleetRows(registry.data)
      : null;
  const fleetRows = liveFleet ?? FLEET_ROWS;
  const fleetIsLive = liveFleet !== null;

  const showcaseSuffix = (live: boolean) =>
    live ? "" : " · showcase data (backend unreachable)";

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
          {!fleetIsLive && (
            <Text
              size="sm"
              style={{ color: colors.textMuted }}
              mb={8}
            >
              Showcase data{showcaseSuffix(false)} -- showing fixture rows.
              Live registry reflects actual registered models.
            </Text>
          )}
          <AboutModelFleet paragraphs={MODEL_FLEET_PARAS} rows={fleetRows} />
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
