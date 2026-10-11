/**
 * <ParkField> - the /parks hero drawing (SPEC-parks section 6.4): a GENERIC field, ink only.
 *
 * What it draws, and what it does not claim:
 *  - foul lines at +/-45 deg, the infield diamond and distance rings at 300-450 ft: reference
 *    structure, NOT any park's walls (the page says so beside the drawing);
 *  - the spray ray in the DRAFT direction, following the slider 1:1 (no transition);
 *  - a hollow ring at the DRAFT input distance, which is the rough client estimate the page sends
 *    the model as hitDistanceFt, labelled as such;
 *  - the model's carry range (shortest to longest park) as a bracket on the ray, drawn in ink when
 *    the drawing shows the SCORED ball, and in ink3 when the hand has moved past it.
 * It never labels a ball "foul": in this spray convention the corpus has home runs out to 52.7 deg,
 * beyond the drawn 45-degree lines, so the lines are a reference, not a ruling.
 *
 * Text is sized in CSS px at the container's real width (the ReliabilityChart useElementSize
 * pattern), so a 390 px phone still reads 10-11 px labels.
 */
import { useElementSize } from "@mantine/hooks";

import type { CarryEnd } from "../../lib/park-ball";

const VB_W = 680;
const VB_H = 400;
const HOME_X = VB_W / 2;
const HOME_Y = 384;
const FT = 0.78; // viewBox units per foot
const RINGS = [300, 350, 400, 450];

function pt(ft: number, deg: number): [number, number] {
  const r = (deg * Math.PI) / 180;
  // + spray is toward LEFT field, which is screen-left.
  return [HOME_X - Math.sin(r) * ft * FT, HOME_Y - Math.cos(r) * ft * FT];
}

function arc(ft: number): string {
  const [x1, y1] = pt(ft, 45);
  const [x2, y2] = pt(ft, -45);
  return `M${x1.toFixed(1)},${y1.toFixed(1)} A${ft * FT},${ft * FT} 0 0 1 ${x2.toFixed(1)},${y2.toFixed(1)}`;
}

export type ParkFieldProps = {
  /** Draft spray, API sign (+ toward left field). */
  sprayDeg: number;
  /** Draft input distance (feet): exactly the hitDistanceFt the page sends. */
  inputDistanceFt: number;
  /** The scored ball's carry range across the parks; null when the champion serves no carry. */
  carry: { min: CarryEnd; max: CarryEnd } | null;
  /** True when the drawing shows the scored ball (draft = scored): ink bracket + labels. */
  settled: boolean;
  /** The accessible description of the SCORED ball. */
  label: string;
  /**
   * Visual-only note beside the ray when not settled ("release to rescore" / "rescoring"); null
   * when nothing is on its way (e.g. the request failed), so the drawing never promises a rescore.
   */
  unsettledNote?: string | null;
};

export function ParkField({
  sprayDeg,
  inputDistanceFt,
  carry,
  settled,
  label,
  unsettledNote = "release to rescore",
}: ParkFieldProps) {
  const { ref, width } = useElementSize();
  // Before measurement (SSR, jsdom, first paint) assume the full viewBox width; the box is
  // pre-sized by its aspect ratio, so the swap causes no layout shift.
  const scale = width > 0 ? width / VB_W : 1;
  const px = (n: number) => n / scale; // CSS px -> viewBox units

  const [lx, ly] = pt(470, 45);
  const [rx, ry] = pt(470, -45);
  const diamond = [[HOME_X, HOME_Y], pt(90, -45), pt(127.3, 0), pt(90, 45)]
    .map(([x, y]) => `${x!.toFixed(1)},${y!.toFixed(1)}`)
    .join(" ");
  const rayEnd = pt(
    Math.max(carry?.max.ft ?? 0, inputDistanceFt) + 10,
    sprayDeg,
  );
  const [ix, iy] = pt(inputDistanceFt, sprayDeg);
  const lo = carry ? pt(carry.min.ft, sprayDeg) : null;
  const hi = carry ? pt(carry.max.ft, sprayDeg) : null;
  // Labels sit on the side of the ray with more room.
  const labelsLeft = sprayDeg < -20;
  const inputSameSide = Math.abs(sprayDeg) > 20;

  return (
    <div ref={ref} className="pk-field">
      <svg
        viewBox={`0 0 ${VB_W} ${VB_H}`}
        role="img"
        aria-label={label}
        preserveAspectRatio="xMidYMid meet"
      >
        {RINGS.map((r) => {
          const [tx, ty] = pt(r, -45);
          return (
            <g key={r}>
              <path className="pk-field__ring" d={arc(r)} />
              <text
                className="pk-field__t"
                x={tx + px(6)}
                y={ty + px(3)}
                fontSize={px(10)}
              >
                {r} ft
              </text>
            </g>
          );
        })}
        <line
          className="pk-field__foul"
          x1={HOME_X}
          y1={HOME_Y}
          x2={lx}
          y2={ly}
        />
        <line
          className="pk-field__foul"
          x1={HOME_X}
          y1={HOME_Y}
          x2={rx}
          y2={ry}
        />
        <polygon className="pk-field__diamond" points={diamond} />
        <polygon
          className="pk-field__plate"
          points={`${HOME_X},${HOME_Y - 4} ${HOME_X + 4},${HOME_Y} ${HOME_X},${HOME_Y + 4} ${HOME_X - 4},${HOME_Y}`}
        />
        <line
          className="pk-field__ray"
          x1={HOME_X}
          y1={HOME_Y}
          x2={rayEnd[0]}
          y2={rayEnd[1]}
        />
        {lo && hi ? (
          <line
            className={
              settled
                ? "pk-field__carry"
                : "pk-field__carry pk-field__carry--draft"
            }
            x1={lo[0]}
            y1={lo[1]}
            x2={hi[0]}
            y2={hi[1]}
          />
        ) : null}
        <circle className="pk-field__input" cx={ix} cy={iy} r={4.5} />
        {settled && carry && lo && hi ? (
          <g textAnchor={labelsLeft ? "end" : "start"}>
            <text
              className="pk-field__l"
              x={hi[0] + (labelsLeft ? -px(14) : px(14))}
              y={hi[1] - px(2)}
              fontSize={px(11)}
            >
              {`${carry.max.ft} ft, ${carry.max.name}`}
            </text>
            <text
              className="pk-field__l"
              x={lo[0] + (labelsLeft ? -px(14) : px(14))}
              y={lo[1] + px(12)}
              fontSize={px(11)}
            >
              {`${carry.min.ft} ft, ${carry.min.name}`}
            </text>
          </g>
        ) : null}
        {settled ? (
          <text
            className="pk-field__t"
            // Far from center the carry labels and the input label share a side (the other side
            // runs into the foul line); the input label then drops below the carry labels.
            x={
              ix +
              (inputSameSide
                ? labelsLeft
                  ? -px(12)
                  : px(12)
                : labelsLeft
                  ? px(12)
                  : -px(12))
            }
            y={iy + (inputSameSide ? px(18) : px(4))}
            fontSize={px(10)}
            textAnchor={
              inputSameSide
                ? labelsLeft
                  ? "end"
                  : "start"
                : labelsLeft
                  ? "start"
                  : "end"
            }
          >
            {`input, about ${Math.round(inputDistanceFt / 5) * 5} ft`}
          </text>
        ) : unsettledNote ? (
          <text
            className="pk-field__t"
            x={(hi ?? [ix, iy])[0] + px(12)}
            y={(hi ?? [ix, iy])[1] - px(6)}
            fontSize={px(10)}
            aria-hidden="true"
          >
            {unsettledNote}
          </text>
        ) : null}
      </svg>
    </div>
  );
}
