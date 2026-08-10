/**
 * /about - slim colophon ([191]/ADR-0017).
 *
 * Provenance ("how this was built"), the stack table, and the rejected
 * alternatives tag cloud. Methodology moved to /models/guide; model fleet
 * is on /ops and the home page fleet strip. The operational discipline and
 * roadmap sections are removed.
 *
 * Data sourcing: editorial fixture content only (ABOUT_META, FACTS_RIBBON,
 * STACK_ROWS, REJECTED_*). The live fleet table moved to /ops.
 */

import { AboutColophonFooter } from "../components/about/about-colophon-footer";
import { LowerThird } from "../components/broadcast/lower-third";
import { AboutFactsRibbon } from "../components/about/about-facts-ribbon";
import { AboutHeader } from "../components/about/about-header";
import { AboutRejectedAlternatives } from "../components/about/about-rejected-alternatives";
import { AboutStackTable } from "../components/about/about-stack-table";
import {
  ABOUT_META,
  FACTS_RIBBON,
  REJECTED_PARA,
  REJECTED_TAGS,
  STACK_ROWS,
} from "../data/about-fixtures";
import { BUILD_DATE, BUILD_SHA } from "../build-info";
import { PageChrome } from "../components/shared/page-chrome";
import { typography, colors } from "../design/broadcast";

import "./about/about.css";

export default function AboutPage() {
  return (
    <PageChrome>
      <AboutHeader
        issueDate={ABOUT_META.issueDate}
        builtBy={ABOUT_META.builtBy}
        edition={ABOUT_META.edition}
        calendar={ABOUT_META.calendar}
        weeklyHours={ABOUT_META.weeklyHours}
      />

      <AboutFactsRibbon cells={FACTS_RIBBON} />
      <p
        style={{
          margin: "4px 0 0",
          fontFamily: typography.fonts.mono,
          fontSize: 11,
          color: colors.textMuted,
        }}
      >
        Showcase data - illustrative project figures, not a live count.
      </p>

      <section aria-labelledby="about-stack-label">
        <div style={{ marginBottom: 12 }}>
          <LowerThird id="about-stack-label">The Stack</LowerThird>
        </div>
        <AboutStackTable rows={STACK_ROWS} />
      </section>

      <section aria-labelledby="about-rejected-label">
        <div style={{ marginBottom: 12 }}>
          <LowerThird id="about-rejected-label">
            Intentionally Not Here
          </LowerThird>
        </div>
        <AboutRejectedAlternatives
          paragraph={REJECTED_PARA}
          tags={REJECTED_TAGS}
        />
      </section>

      <AboutColophonFooter
        buildSha={BUILD_SHA}
        buildDate={BUILD_DATE}
        repoPlaceholder={ABOUT_META.repoPlaceholder}
      />
    </PageChrome>
  );
}
