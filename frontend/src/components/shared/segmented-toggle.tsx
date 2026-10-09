/**
 * <SegmentedToggle> - the one filter / metric toggle: a role="group" of
 * aria-pressed buttons. Colors, the pressed fill, the gated hover and the
 * press feedback all come from `.bp-seg` / `.bp-seg__btn` / `.bp-pressable`
 * in src/design/interaction.css; the inline style carries type and layout only
 * (an inline color or background would outrank the hover and pressed rules).
 */
import { typography } from "../../design/broadcast";

export type SegmentedOption<T extends string> = { key: T; label: string };

export function SegmentedToggle<T extends string>({
  options,
  value,
  onChange,
  ariaLabel,
}: {
  options: readonly SegmentedOption<T>[];
  value: T;
  onChange: (k: T) => void;
  ariaLabel: string;
}) {
  return (
    <div role="group" aria-label={ariaLabel} className="bp-seg">
      {options.map((o) => (
        <button
          key={o.key}
          type="button"
          aria-pressed={value === o.key}
          onClick={() => onChange(o.key)}
          className="bp-seg__btn bp-pressable"
          style={{
            fontFamily: typography.fonts.mono,
            fontWeight: typography.weights.medium,
            fontSize: 12,
            letterSpacing: typography.tracking.label,
            padding: "6px 14px",
          }}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}
