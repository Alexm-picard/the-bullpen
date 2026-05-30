/**
 * /home — the tech-product redesign (2026-05-25).
 *
 * Four sections in vertical order:
 *   1. Hero (asymmetric 5fr/7fr): pitch + CTAs on the left, <LivePredictionWidget> right.
 *   2. "What's working today" — 3-card grid (Parks / Ops / Methodology) + honest one-liner.
 *   3. "How a prediction gets here" — 4 numbered steps left, real curl + JSON response right.
 *   4. Footer: one line — credit + GitHub link.
 *
 * Constraints honoured:
 *   - Home is the entry chunk — kept as default export, NOT lazy-loaded.
 *   - One <Title order={1}> only (the hero h1). Static a11y audit caps at ≤3.
 *   - No hex codes — every color via tokens.
 *   - The widget owns its own data fetch; this page composes presentational pieces.
 *   - Mobile h1 reduces 56→40 via a CSS clamp() so the type ramp doesn't blow out at 375px.
 *
 * The page composes its own grid via inline style + a small CSS class on the wrapper
 * for responsive collapse; no new tailwind utilities needed.
 */
import { Anchor, Box, Stack, Text, Title } from "@mantine/core";
import { Link } from "react-router-dom";

import { CodeBlock } from "../components/shared/code-block";
import { DestinationCard } from "../components/shared/destination-card";
import { HeroEyebrow } from "../components/shared/hero-eyebrow";
import { NumberedStep } from "../components/shared/numbered-step";
import { SectionHeader } from "../components/shared/section-header";
import { colors, layouts, spacing, typography } from "../design/tokens";

import { LivePredictionWidget } from "./home/live-prediction-widget";

const CURL_EXAMPLE = `$ curl -X POST \\
  https://thebullpen.net/v1/predict/batted-ball \\
  -H "Content-Type: application/json" \\
  -d '{
    "launchSpeedMph": 102.3,
    "launchAngleDeg": 28.0,
    "releaseSpeedMph": 95.0,
    "parkId": "FENWAY",
    "stand": "R"
  }'`;

const JSON_EXAMPLE = `{
  "probHr": 0.341,
  "modelName": "batted_ball_v1",
  "modelVersion": "2025.11.04-shadow",
  "latencyMicros": 2837,
  "correlationId": "9f3a1c..."
}`;

const STEPS = [
  {
    title: "Pull Statcast & ingest",
    description:
      "Daily pybaseball pull into ClickHouse. Streaming temporal cutoff on every feature so no future leaks into history.",
  },
  {
    title: "Train & calibrate",
    description:
      "LightGBM 5-class for pitch outcome, MLP with 30 park heads for batted-ball. Isotonic calibration on every head. Rolling-origin CV — never random splits.",
  },
  {
    title: "Register & shadow",
    description:
      "ONNX export + feature_pipeline.json + Parquet snapshot land in R2. Schema hash must match prod. New models default to SHADOW until promotion criteria pass.",
  },
  {
    title: "Serve & log",
    description:
      "ONNX Runtime Java in-process. Every prediction logged with model id, latency, and a correlation id you can grep on /ops.",
  },
];

export default function HomePage() {
  return (
    <Box
      style={{
        backgroundColor: colors.bgBase,
        minHeight: "calc(100vh - 56px)",
      }}
    >
      <style>{`
        .home-hero-grid {
          display: grid;
          grid-template-columns: 5fr 7fr;
          gap: ${spacing[5]}px;
          align-items: center;
        }
        .home-meta-grid {
          display: grid;
          grid-template-columns: repeat(3, 1fr);
          gap: ${spacing[3]}px;
        }
        .home-method-grid {
          display: grid;
          grid-template-columns: 1fr 1fr;
          gap: ${spacing[5]}px;
          align-items: start;
        }
        .home-h1 {
          font-size: clamp(${typography.scale[6]}px, 6vw, ${typography.scale[7]}px);
        }
        @media (max-width: 900px) {
          .home-hero-grid { grid-template-columns: 1fr; gap: ${spacing[5]}px; }
          .home-meta-grid { grid-template-columns: 1fr; }
          .home-method-grid { grid-template-columns: 1fr; gap: ${spacing[5]}px; }
        }
      `}</style>

      <Box
        style={{
          maxWidth: layouts.analyticalMaxWidth,
          margin: "0 auto",
          padding: `${spacing[7]}px ${spacing[4]}px`,
        }}
      >
        {/* ─────────────── 1. Hero ─────────────── */}
        <section className="home-hero-grid">
          <Stack gap={spacing[4]}>
            <HeroEyebrow>Self-hosted · ML systems wrapper</HeroEyebrow>
            <Title
              order={1}
              className="home-h1"
              style={{
                fontFamily: typography.fonts.display,
                fontWeight: typography.weights.bold,
                letterSpacing: "-0.03em",
                lineHeight: 1.05,
                color: colors.textStrong,
                margin: 0,
              }}
            >
              Calibrated baseball predictions, with the registry, drift, and
              postmortems to back them.
            </Title>
            <Text
              style={{
                fontFamily: typography.fonts.body,
                fontSize: typography.scale[3], // 20
                color: colors.textMuted,
                lineHeight: 1.5,
                maxWidth: 540,
              }}
            >
              The Bullpen is a portfolio-grade ML systems build: a real model
              registry, A/B routing, drift detection, and a four-fold
              rolling-origin eval — all serving a calibrated batted-ball and
              pitch outcome model.
            </Text>
            <div
              style={{
                display: "flex",
                gap: spacing[3],
                alignItems: "center",
                flexWrap: "wrap",
              }}
            >
              <Anchor
                component={Link}
                to="/about"
                style={{
                  display: "inline-flex",
                  alignItems: "center",
                  gap: spacing[1],
                  padding: `${spacing[2]}px ${spacing[4]}px`,
                  backgroundColor: colors.scarlet,
                  color: colors.bgSheet,
                  fontFamily: typography.fonts.body,
                  fontWeight: typography.weights.semibold,
                  fontSize: typography.scale[2], // 16
                  borderRadius: 6,
                  textDecoration: "none",
                  letterSpacing: "-0.01em",
                }}
              >
                See the methodology
              </Anchor>
              <Anchor
                component={Link}
                to="/parks"
                style={{
                  display: "inline-flex",
                  alignItems: "center",
                  gap: spacing[1],
                  padding: `${spacing[2]}px ${spacing[3]}px`,
                  color: colors.textStrong,
                  fontFamily: typography.fonts.body,
                  fontWeight: typography.weights.medium,
                  fontSize: typography.scale[2], // 16
                  textDecoration: "none",
                  letterSpacing: "-0.01em",
                }}
              >
                Browse parks →
              </Anchor>
            </div>
          </Stack>
          <LivePredictionWidget />
        </section>

        {/* ─────────────── 2. What's working today ─────────────── */}
        <section style={{ marginTop: spacing[8] }}>
          <SectionHeader
            eyebrow="Phase 2a · shipped"
            title="What's working today"
            lede="Two pages are real, with calibrated models behind them. The rest of the surface is honest about where it stands."
          />
          <Box className="home-meta-grid" style={{ marginTop: spacing[5] }}>
            <DestinationCard
              to="/parks"
              eyebrow="Live"
              title="Park HR explorer"
              description="Move launch speed, angle, and release speed. Watch every park's HR probability recompute in real time — five parks wired, calibrated MLP."
              stat="5 parks · ECE 0.0036"
            />
            <DestinationCard
              to="/ops"
              eyebrow="Live"
              title="Ops dashboard"
              description="Model registry, A/B routing, drift jobs, and prediction logs — the systems wrapper that makes a model promotion auditable."
              stat="2 LIVE · 1 SHADOW"
            />
            <DestinationCard
              to="/about"
              eyebrow="Read"
              title="Methodology"
              description="How the rolling-origin CV works, why feature schema hashes are enforced at registration, and what 'calibrated' actually means here."
              stat="6 ADRs · 134 decisions"
            />
          </Box>
          <Text
            style={{
              marginTop: spacing[4],
              fontFamily: typography.fonts.body,
              fontSize: typography.scale[1], // 14
              color: colors.textMuted,
              maxWidth: 720,
              lineHeight: 1.55,
            }}
          >
            <Text
              component="span"
              style={{
                fontFamily: typography.fonts.mono,
                color: colors.textDefault,
                fontWeight: typography.weights.semibold,
              }}
            >
              Honest status:
            </Text>{" "}
            <Anchor
              component={Link}
              to="/players"
              style={{ color: colors.textDefault }}
            >
              /players
            </Anchor>{" "}
            and{" "}
            <Anchor
              component={Link}
              to="/games"
              style={{ color: colors.textDefault }}
            >
              /games
            </Anchor>{" "}
            are scaffolded — search and tables work against the registry, but
            the per-player and per-game models are Phase 2b/2c work. They render
            today against the toy model so the spine is intact.
          </Text>
        </section>

        {/* ─────────────── 3. How a prediction gets here ─────────────── */}
        <section style={{ marginTop: spacing[8] }}>
          <SectionHeader
            eyebrow="Pipeline"
            title="How a prediction gets here"
            lede="Four steps from raw Statcast to a calibrated probability behind a single POST."
          />
          <Box className="home-method-grid" style={{ marginTop: spacing[5] }}>
            <Stack gap={spacing[4]}>
              {STEPS.map((step, i) => (
                <NumberedStep
                  key={step.title}
                  index={i + 1}
                  title={step.title}
                  description={step.description}
                />
              ))}
            </Stack>
            <Stack gap={spacing[3]}>
              <CodeBlock label="Request" code={CURL_EXAMPLE} />
              <CodeBlock label="Response" code={JSON_EXAMPLE} />
            </Stack>
          </Box>
        </section>

        {/* ─────────────── 4. Footer ─────────────── */}
        <Box
          component="footer"
          style={{
            marginTop: spacing[8],
            paddingTop: spacing[4],
            borderTop: `1px solid ${colors.bgEmphasis}`,
          }}
        >
          <Text
            style={{
              fontFamily: typography.fonts.mono,
              fontSize: typography.scale[0], // 12
              color: colors.textMuted,
              letterSpacing: "0.02em",
            }}
          >
            The Bullpen · solo build by alex picard ·{" "}
            <Anchor
              href="https://github.com/Alexm-picard/thebullpen"
              target="_blank"
              rel="noopener noreferrer"
              style={{ color: colors.scarlet }}
            >
              source on github →
            </Anchor>
          </Text>
        </Box>
      </Box>
    </Box>
  );
}
