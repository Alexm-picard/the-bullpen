/**
 * /about — long-form editorial page (leaf 4f).
 *
 * Six sections, each wrapped in `<EditorialSection>` so they share the
 * 720-px measure + Source Serif 4 headlines + vertical rhythm. Inline SVG
 * architecture diagram between Models and Eval methodology.
 *
 * Prose intentionally short: <500 words per section. Recruiters skim.
 * Drawn from docs/design.md §1, §2, §3, §5, §11.
 */
import { Anchor, Box, List, Stack, Text, Title } from "@mantine/core";
import { Link } from "react-router-dom";

import { EditorialSection } from "../components/editorial/editorial-section";
import { colors, typography } from "../design/tokens";

export default function AboutPage() {
  return (
    <Box>
      <Stack gap={0}>
        <HeaderBlock />
        <IntroSection />
        <ModelsSection />
        <ArchitectureDiagram />
        <TrainingDataSection />
        <EvalMethodologySection />
        <DriftRetrainingSection />
        <WhatsNextSection />
      </Stack>
    </Box>
  );
}

function HeaderBlock() {
  return (
    <Box
      component="header"
      style={{
        maxWidth: 720,
        marginLeft: "auto",
        marginRight: "auto",
        paddingTop: 96,
        paddingBottom: 32,
      }}
    >
      <Stack gap="md">
        <Title
          order={6}
          tt="uppercase"
          c="dimmed"
          style={{ margin: 0, letterSpacing: "0.08em" }}
        >
          The Bullpen — methodology
        </Title>
        <Title
          order={1}
          style={{
            margin: 0,
            fontSize: 64,
            lineHeight: 1.05,
            fontWeight: 700,
          }}
        >
          A serving wrapper around three baseball models.
        </Title>
        <Text c="dimmed" size="lg">
          Self-hosted, eight months of calendar build, operated across at least
          one MLB season for a real drift postmortem. Not a SaaS product, not a
          betting tool, not a research contribution.
        </Text>
      </Stack>
    </Box>
  );
}

function IntroSection() {
  return (
    <EditorialSection
      eyebrow="01 — What this is"
      title="The project, in one paragraph"
    >
      <Text>
        The Bullpen is a baseball-prediction service built primarily to learn
        and demonstrate the operational discipline around shipping ML systems —
        a model registry, A/B routing, drift detection, automated retraining
        triggers, and an honest set of evaluation artifacts. Three models are
        served behind one Spring Boot app: a multinomial pitch-outcome head, a
        per-park batted-ball MLP, and a calibrated baseline. The models are the
        excuse; the wrapper is the project.
      </Text>
      <Text>
        Hiring framing matters. The README and this page should read to a
        recruiter as "I built a real production ML system and ran it for a
        season," not "I trained a model in a notebook." Every locked technical
        choice has a written rationale in{" "}
        <Anchor
          href="https://github.com/"
          target="_blank"
          rel="noopener noreferrer"
        >
          docs/decisions.md
        </Anchor>{" "}
        — including the alternatives I rejected.
      </Text>
    </EditorialSection>
  );
}

function ModelsSection() {
  return (
    <EditorialSection
      eyebrow="02 — Models"
      title="Three models, two heads, one wrapper"
    >
      <Text>
        <strong>Pitch outcome (pre-pitch).</strong> LightGBM, 5-class
        multinomial (ball / called strike / swinging strike / foul / in play),
        calibrated by isotonic regression on a held-out fold. Predicts the
        distribution of outcomes from state available before the pitch is
        thrown.{" "}
        <Anchor component={Link} to="/ops">
          See registry →
        </Anchor>
      </Text>
      <Text>
        <strong>Pitch outcome (post-pitch).</strong> Same 5-class shape, with
        early-flight features (release speed, plate location, spin rate) wired
        in. A separate registered model — never one model with feature masking
        (rule 9).
      </Text>
      <Text>
        <strong>Batted-ball.</strong> A small MLP with a shared backbone and 30
        per-park heads. Takes launch parameters and a park id; returns P(out /
        1B / 2B / 3B / HR). The Phase-1 toy version serves the spine; the real
        model lands in Phase 2c. A logistic-regression baseline is always
        co-registered to bound how much the neural model is buying.
      </Text>
    </EditorialSection>
  );
}

function ArchitectureDiagram() {
  return (
    <Box
      style={{
        maxWidth: 720,
        marginLeft: "auto",
        marginRight: "auto",
        paddingTop: 16,
        paddingBottom: 32,
      }}
    >
      <Stack gap="xs">
        <Text
          size="xs"
          c="dimmed"
          tt="uppercase"
          style={{ letterSpacing: "0.08em" }}
        >
          System architecture
        </Text>
        <Box
          style={{
            border: `1px solid ${colors.bgEmphasis}`,
            borderRadius: 8,
            padding: 16,
            backgroundColor: colors.bgElevated,
          }}
        >
          <svg
            viewBox="0 0 640 200"
            width="100%"
            role="img"
            aria-label="Data flows from Statcast and MLB Stats API into ClickHouse, training emits ONNX, Spring serves predictions to the React frontend"
            style={{ fontFamily: typography.fonts.data, fontSize: 11 }}
          >
            <SourceBox x={10} y={20} w={110} label="Statcast" />
            <SourceBox x={10} y={80} w={110} label="MLB Stats API" />
            <SourceBox x={10} y={140} w={110} label="Weather" />

            <CenterBox
              x={170}
              y={20}
              w={130}
              label="ClickHouse"
              sub="pitches · drift"
            />
            <CenterBox
              x={170}
              y={130}
              w={130}
              label="Training (Py)"
              sub="rolling-CV · ONNX"
            />

            <CenterBox
              x={340}
              y={20}
              w={130}
              label="Registry (SQLite)"
              sub="versions · A/B"
            />
            <CenterBox
              x={340}
              y={130}
              w={130}
              label="Spring (Java)"
              sub="ONNX Runtime · API"
            />

            <CenterBox
              x={510}
              y={75}
              w={120}
              label="React + Mantine"
              sub="this site"
            />

            <Arrow x1={120} y1={45} x2={170} y2={45} />
            <Arrow x1={120} y1={105} x2={170} y2={155} />
            <Arrow x1={120} y1={155} x2={170} y2={155} />
            <Arrow x1={300} y1={155} x2={340} y2={155} />
            <Arrow x1={300} y1={45} x2={340} y2={45} />
            <Arrow x1={405} y1={70} x2={405} y2={130} />
            <Arrow x1={470} y1={155} x2={510} y2={105} />
            <Arrow x1={470} y1={45} x2={510} y2={95} />
          </svg>
        </Box>
      </Stack>
    </Box>
  );
}

function SourceBox({
  x,
  y,
  w,
  label,
}: {
  x: number;
  y: number;
  w: number;
  label: string;
}) {
  return (
    <g>
      <rect
        x={x}
        y={y}
        width={w}
        height={40}
        rx={4}
        fill={colors.bgSubtle}
        stroke={colors.bgEmphasis}
      />
      <text
        x={x + w / 2}
        y={y + 24}
        textAnchor="middle"
        fill={colors.textDefault}
      >
        {label}
      </text>
    </g>
  );
}

function CenterBox({
  x,
  y,
  w,
  label,
  sub,
}: {
  x: number;
  y: number;
  w: number;
  label: string;
  sub?: string;
}) {
  return (
    <g>
      <rect
        x={x}
        y={y}
        width={w}
        height={50}
        rx={4}
        fill={colors.bgElevated}
        stroke={colors.bgEmphasis}
      />
      <text
        x={x + w / 2}
        y={y + 20}
        textAnchor="middle"
        fill={colors.textStrong}
        fontWeight={600}
      >
        {label}
      </text>
      {sub ? (
        <text
          x={x + w / 2}
          y={y + 36}
          textAnchor="middle"
          fill={colors.textMuted}
        >
          {sub}
        </text>
      ) : null}
    </g>
  );
}

function Arrow({
  x1,
  y1,
  x2,
  y2,
}: {
  x1: number;
  y1: number;
  x2: number;
  y2: number;
}) {
  return (
    <line
      x1={x1}
      y1={y1}
      x2={x2}
      y2={y2}
      stroke={colors.textMuted}
      strokeWidth={1}
      markerEnd="url(#arrow)"
    />
  );
}

function TrainingDataSection() {
  return (
    <EditorialSection
      eyebrow="03 — Training data"
      title="Statcast 2015–2025, with deliberate provenance"
    >
      <Text>
        The pitch-level dataset is{" "}
        <Anchor
          href="https://baseballsavant.mlb.com/"
          target="_blank"
          rel="noopener noreferrer"
        >
          Statcast
        </Anchor>{" "}
        pulled via{" "}
        <Anchor
          href="https://pypi.org/project/pybaseball/"
          target="_blank"
          rel="noopener noreferrer"
        >
          pybaseball
        </Anchor>{" "}
        — ~200 GB by the time the historical backfill lands. Weather is joined
        from a free meteorology source. Roster + game schedule are the MLB Stats
        API. Training snapshots are immutable Parquet files in object storage
        (Cloudflare R2 in prod, MinIO offline) so any model in the registry can
        be retrained bitwise from its hash.
      </Text>
      <Text>
        Data is downloaded for personal research use only. Public outputs of
        this project (predictions, model artifacts, this site) are derived
        analytics, not redistribution of the underlying play-by-play data.
      </Text>
    </EditorialSection>
  );
}

function EvalMethodologySection() {
  return (
    <EditorialSection
      eyebrow="04 — Evaluation"
      title="Rolling-origin cross-validation, never random splits"
    >
      <Text>
        Every model is evaluated by 4-fold rolling-origin cross-validation
        across 2015–2025. Each fold trains on all earlier dates and validates on
        a later contiguous window — no date overlap, no random split. The
        within-fold split is by date too; never by game or by pitch (a single
        random-pitch split silently leaks future at-bats into training).
      </Text>
      <Text>
        Headline numbers — Brier, ECE, log loss — are reported as mean ± std
        across folds, and a per-fold table sits in each model's registry detail
        on the{" "}
        <Anchor component={Link} to="/ops">
          Ops page
        </Anchor>
        . Four leakage tests run in CI: future contamination, shuffled-target,
        calendar-date trace, and ID consistency. None of them are negotiable.
      </Text>
    </EditorialSection>
  );
}

function DriftRetrainingSection() {
  return (
    <EditorialSection
      eyebrow="05 — Drift + retraining"
      title="Drift detected, retrain queued, promotion gated"
    >
      <Text>
        Drift is measured in two ways: PSI per feature on a 7-day window against
        the training distribution, and calibration delta on the prediction logs
        against the training-fold baseline. When either crosses its pre-declared
        threshold for a sustained window, a row is enqueued in the retraining
        queue with the trigger id, model name, and the metric values that fired
        it.
      </Text>
      <Text>
        The worker claims the next queued row atomically (SQLite single-writer +
        UPDATE-WHERE-status='queued'), runs the matching training pipeline, and
        registers the new candidate.{" "}
        <strong>Promotion stays human-gated.</strong> Decision [44]: automated
        retraining triggers, manual promotions. The new version lands as
        CANDIDATE and shows up on the Ops dashboard for review — never
        auto-routed in front of users.
      </Text>
    </EditorialSection>
  );
}

function WhatsNextSection() {
  return (
    <EditorialSection
      eyebrow="06 — What's next"
      title="v1.5 ideas, cherry-picked"
    >
      <List spacing="xs">
        <List.Item>
          A 30-park batted-ball MLP that natively emits 30 outputs in one ONNX
          call — replaces the v1 per-park loop on the Park Explorer endpoint.
        </List.Item>
        <List.Item>
          Truth-joining prediction_log to pitches by an indexed pitch_id so the
          calibration view and the per-player history table can show actual vs
          predicted side-by-side.
        </List.Item>
        <List.Item>
          The live MLB-Stats-API poller wired to the existing GameStateMachine
          so /games/:id actually populates from real games.
        </List.Item>
        <List.Item>
          A small "admin override" page wrapping the A/B traffic-percent slider
          behind HTTP Basic — Ops dashboard stays public-read, admin writes stay
          gated.
        </List.Item>
        <List.Item>
          Hyperparameter search in the retraining job (fixed-HP today per
          decision [81]).
        </List.Item>
        <List.Item>
          Per-game weather pull replacing the per-park annual default atmosphere
          (Phase 2c.4).
        </List.Item>
      </List>
    </EditorialSection>
  );
}
