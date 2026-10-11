/**
 * <ShowcaseNote> - the D4 honesty label for hand-written fixture sections ([195] editorial skin):
 * an inline boxed "Showcase" tag followed by the reason in the serif italic note voice. Always
 * visible, never colour-coded, never behind a tooltip.
 */
import type { ReactNode } from "react";

export function ShowcaseNote({ children }: { children: ReactNode }) {
  return (
    <p className="pl-showcase">
      <span className="pl-tag">Showcase</span>
      {children}
    </p>
  );
}
