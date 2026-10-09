// @vitest-environment jsdom
/**
 * <SegmentedToggle>: the shared role="group" of aria-pressed buttons. Colors and
 * the pressed fill belong to `.bp-seg` / `.bp-seg__btn` (interaction.css), so the
 * buttons must carry no inline color or background that would outrank them.
 */
import "@testing-library/jest-dom/vitest";

import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { SegmentedToggle } from "./segmented-toggle";

afterEach(cleanup);

const OPTIONS = [
  { key: "a", label: "Alpha" },
  { key: "b", label: "Beta" },
] as const;

describe("SegmentedToggle", () => {
  it("renders a labelled group with one aria-pressed button per option", () => {
    render(
      <SegmentedToggle
        options={OPTIONS}
        value="a"
        onChange={() => {}}
        ariaLabel="Pick one"
      />,
    );
    const group = screen.getByRole("group", { name: "Pick one" });
    expect(group).toHaveClass("bp-seg");
    expect(screen.getByRole("button", { name: "Alpha" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(screen.getByRole("button", { name: "Beta" })).toHaveAttribute(
      "aria-pressed",
      "false",
    );
  });

  it("reports the clicked key", () => {
    const onChange = vi.fn();
    render(
      <SegmentedToggle
        options={OPTIONS}
        value="a"
        onChange={onChange}
        ariaLabel="Pick one"
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "Beta" }));
    expect(onChange).toHaveBeenCalledWith("b");
  });

  it("leaves color and background to the classes", () => {
    render(
      <SegmentedToggle
        options={OPTIONS}
        value="b"
        onChange={() => {}}
        ariaLabel="Pick one"
      />,
    );
    for (const btn of screen.getAllByRole("button")) {
      expect(btn).toHaveClass("bp-seg__btn", "bp-pressable");
      expect(btn).toHaveAttribute("type", "button");
      expect(btn.style.color).toBe("");
      expect(btn.style.backgroundColor).toBe("");
    }
  });
});
