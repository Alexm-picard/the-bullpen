/**
 * <InfraRibbon> — the navy lower-third strip showing the host's stateful
 * services at a glance.
 *
 * Visual: identical chip-strip vocabulary as <ModelFleetRibbon> (navy bar with
 * navyDeep dividers, Saira label top, mono detail below, state badge right).
 * Distinct component because:
 *   - non-interactive (no /ops/clickhouse routes — these aren't navigation)
 *   - different state vocabulary (UP / DEGRADED / DOWN, not LIVE / SHADOW / OK)
 * Generalising ModelFleetRibbon with a `variant` prop would balloon its surface
 * area for no shared call sites. Two narrow components beats one wide one.
 *
 * State colors:
 *   UP        — condFormat.good3 (the cellColor good ramp's strong green)
 *   DEGRADED  — viz.categorical[3] (the gold slot — distinct from scarlet)
 *   DOWN      — scarlet (team accent, matches LIVE in the model ribbon for
 *               the operator's "this is the urgent color" mental model)
 */

import type { InfraService, InfraServiceState } from "../../data/ops-fixtures";
import { colors, typography } from "../../design/tokens";

export type InfraRibbonProps = {
  services: InfraService[];
};

function stateColor(state: InfraServiceState): string {
  if (state === "UP") return colors.condFormat.good3;
  if (state === "DEGRADED") return colors.viz.categorical[3]; // gold
  return colors.scarlet;
}

export function InfraRibbon({ services }: InfraRibbonProps) {
  return (
    <nav
      className="ops-infra-ribbon"
      aria-label="Infrastructure services"
      style={{
        backgroundColor: colors.navy,
        display: "grid",
        gridTemplateColumns: `repeat(${services.length}, 1fr)`,
        columnGap: 1,
        borderRadius: 2,
      }}
    >
      {services.map((svc, i) => (
        <div
          key={svc.id}
          className="ops-infra-ribbon__chip"
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            padding: "10px 14px",
            color: colors.textOnNavy,
            backgroundColor: colors.navy,
            borderRight:
              i < services.length - 1 ? `1px solid ${colors.navyDeep}` : "none",
            minHeight: 48,
          }}
        >
          <div style={{ display: "flex", flexDirection: "column", gap: 2 }}>
            <span
              style={{
                fontFamily: typography.fonts.display,
                fontSize: 13,
                fontWeight: typography.weights.bold,
                textTransform: "uppercase",
                letterSpacing: "0.06em",
                color: colors.textOnNavy,
              }}
            >
              {svc.label}
            </span>
            <span
              style={{
                fontFamily: typography.fonts.mono,
                fontSize: 11,
                color: colors.silver,
                letterSpacing: "0.02em",
              }}
            >
              {svc.detail}
            </span>
          </div>
          <span
            data-state={svc.state}
            style={{
              fontFamily: typography.fonts.mono,
              fontSize: 11,
              fontWeight: typography.weights.bold,
              letterSpacing: "0.06em",
              textTransform: "uppercase",
              color: stateColor(svc.state),
              padding: "2px 6px",
              border: `1px solid ${stateColor(svc.state)}`,
              borderRadius: 2,
              whiteSpace: "nowrap",
            }}
          >
            {svc.state}
          </span>
        </div>
      ))}
    </nav>
  );
}
