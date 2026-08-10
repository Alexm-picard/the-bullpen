import type { CSSProperties } from "react";

import { colors } from "../../design/broadcast";

const DOT: CSSProperties = {
  display: "inline-block",
  width: 8,
  height: 8,
  borderRadius: "50%",
  backgroundColor: colors.live,
};

export function LiveDot({ style }: { style?: CSSProperties } = {}) {
  return (
    <span
      className="broadcast-live-dot"
      style={{ ...DOT, ...style }}
      aria-hidden="true"
    />
  );
}
