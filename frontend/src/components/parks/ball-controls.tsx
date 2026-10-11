/**
 * <BallControls> - the four /parks inputs (SPEC-parks section 5): exit velocity, launch angle and
 * direction as sliders, bat side as two underlined toggles.
 *
 * Each slider's onChange moves the DRAFT (the drawing follows the hand); onChangeEnd ENDS the
 * gesture and the hook decides when to send (use-ball-commit.ts). The input source is captured on
 * the wrapper in the capture phase, before Mantine's own handlers run: mouse/touch down = pointer,
 * keydown = key.
 *
 * Direction mapping: the API's spray is + toward LEFT field, and left field is the drawing's left,
 * so the slider's value is -spray. Dragging left moves the ball toward left field. The printed
 * value is words, never a signed number and never pull/oppo (those swap with handedness).
 */
import { Slider } from "@mantine/core";
import { useRef } from "react";

import { SPRAY_LIMIT_DEG } from "../../api/parks";
import { sprayValueText, sprayWords, type Ball } from "../../lib/park-ball";

import type { BallField, InputSource } from "./use-ball-commit";

type Props = {
  draft: Ball;
  onMove: (field: BallField, value: number) => void;
  onEnd: (field: BallField, value: number, from: InputSource) => void;
  onBegin: (from: InputSource) => void;
  onStand: (stand: Ball["stand"]) => void;
};

const SLIDER_CLASSES = {
  root: "pk-slider",
  track: "pk-slider__track",
  bar: "pk-slider__bar",
  thumb: "pk-slider__thumb",
};

function BallSlider({
  id,
  label,
  readout,
  min,
  max,
  value,
  ends,
  valueText,
  onChange,
  onChangeEnd,
  onBegin,
}: {
  id: string;
  label: string;
  readout: React.ReactNode;
  min: number;
  max: number;
  value: number;
  ends: [string, string];
  valueText: (v: number) => string;
  onChange: (v: number) => void;
  onChangeEnd: (v: number, from: InputSource) => void;
  onBegin: (from: InputSource) => void;
}) {
  const source = useRef<InputSource>("pointer");
  const mark = (from: InputSource) => {
    source.current = from;
    onBegin(from);
  };
  return (
    <div
      className="pk-ctl"
      onMouseDownCapture={() => mark("pointer")}
      onTouchStartCapture={() => mark("pointer")}
      onKeyDownCapture={() => mark("key")}
    >
      <p className="pk-ctl__label" id={`${id}-label`}>
        <span>{label}</span>
        <span className="pk-ctl__value" aria-hidden="true">
          {readout}
        </span>
      </p>
      <Slider
        classNames={SLIDER_CLASSES}
        min={min}
        max={max}
        step={1}
        value={value}
        label={null}
        thumbLabel={label}
        thumbValueText={valueText}
        onChange={onChange}
        onChangeEnd={(v) => onChangeEnd(v, source.current)}
      />
      <p className="pk-ctl__ends" aria-hidden="true">
        <span>{ends[0]}</span>
        <span>{ends[1]}</span>
      </p>
    </div>
  );
}

export function BallControls({
  draft,
  onMove,
  onEnd,
  onBegin,
  onStand,
}: Props) {
  return (
    <div className="pk-controls">
      <BallSlider
        id="pk-ev"
        label="Exit velocity"
        readout={
          <>
            {draft.launchSpeedMph}
            <small>mph</small>
          </>
        }
        min={40}
        max={125}
        value={draft.launchSpeedMph}
        ends={["40", "125"]}
        valueText={(v) => `${v} miles per hour`}
        onChange={(v) => onMove("launchSpeedMph", v)}
        onChangeEnd={(v, from) => onEnd("launchSpeedMph", v, from)}
        onBegin={onBegin}
      />
      <BallSlider
        id="pk-la"
        label="Launch angle"
        readout={
          <>
            {draft.launchAngleDeg}
            <small>deg</small>
          </>
        }
        min={-20}
        max={60}
        value={draft.launchAngleDeg}
        ends={["-20", "60"]}
        valueText={(v) => `${Math.abs(v)} degrees ${v < 0 ? "down" : "up"}`}
        onChange={(v) => onMove("launchAngleDeg", v)}
        onChangeEnd={(v, from) => onEnd("launchAngleDeg", v, from)}
        onBegin={onBegin}
      />
      <BallSlider
        id="pk-dir"
        label="Direction"
        readout={
          <span className="pk-ctl__words">
            {sprayWords(draft.sprayAngleDeg)}
          </span>
        }
        min={-SPRAY_LIMIT_DEG}
        max={SPRAY_LIMIT_DEG}
        // -spray: the slider's left end is left field, as in the drawing.
        value={-draft.sprayAngleDeg}
        ends={["Left field", "Right field"]}
        valueText={(v) => sprayValueText(-v)}
        onChange={(v) => onMove("sprayAngleDeg", -v)}
        onChangeEnd={(v, from) => onEnd("sprayAngleDeg", -v, from)}
        onBegin={onBegin}
      />
      <div className="pk-ctl">
        <p className="pk-ctl__label" id="pk-stand-label">
          <span>Bat side</span>
        </p>
        <div
          className="pl-seg pk-stand"
          role="group"
          aria-labelledby="pk-stand-label"
        >
          {(["R", "L"] as const).map((s) => (
            <button
              key={s}
              type="button"
              className="pl-facet"
              aria-pressed={draft.stand === s}
              onClick={() => onStand(s)}
            >
              {s === "R" ? "Right" : "Left"}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
