import { useMantineColorScheme } from "@mantine/core";
import { useCallback, useEffect, useState, type ReactNode } from "react";
import { flushSync } from "react-dom";

import { ThemeContext } from "./theme-context";

type Theme = "light" | "dark";

const STORAGE_KEY = "bullpen-theme";

function getInitialTheme(): Theme {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored === "light" || stored === "dark") return stored;
  } catch {
    // localStorage unavailable
  }
  if (
    typeof window !== "undefined" &&
    window.matchMedia("(prefers-color-scheme: light)").matches
  ) {
    return "light";
  }
  return "dark";
}

/**
 * Stamp the theme on <html> synchronously. Called from the state initializer
 * (before the first paint of the React tree) and from the toggle, so the
 * palette never flips a frame after the content does. tokens.css also follows
 * the OS preference via @media before any JS runs.
 */
function applyTheme(theme: Theme): void {
  const root = document.documentElement;
  root.dataset.theme = theme;
  root.style.colorScheme = theme;
}

function prefersReducedMotion(): boolean {
  return (
    typeof window !== "undefined" &&
    window.matchMedia("(prefers-reduced-motion: reduce)").matches
  );
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setTheme] = useState<Theme>(() => {
    const initial = getInitialTheme();
    if (typeof document !== "undefined") applyTheme(initial);
    return initial;
  });
  // [192] toggle + Mantine: Mantine controls (inputs, segmented controls, the
  // Drawer) derive their own palette from data-mantine-color-scheme, which was
  // never synced to the toggle before - they rendered light-scheme on the dark
  // field. Keep the two in step.
  const { setColorScheme } = useMantineColorScheme();

  useEffect(() => {
    applyTheme(theme);
    setColorScheme(theme);
    try {
      localStorage.setItem(STORAGE_KEY, theme);
    } catch {
      // localStorage unavailable
    }
  }, [theme, setColorScheme]);

  // The whole palette moves at once, so the flip is a 200ms cross-fade of the
  // page (View Transitions; a plain swap where unsupported or under reduced
  // motion). data-theme-switching suspends per-element transitions for the
  // frame of the flip so the 200ms cell cross-fades cannot lag the page.
  const toggle = useCallback(() => {
    const root = document.documentElement;
    const flip = () => {
      root.setAttribute("data-theme-switching", "");
      flushSync(() => {
        setTheme((t) => {
          const next: Theme = t === "dark" ? "light" : "dark";
          applyTheme(next);
          return next;
        });
      });
      requestAnimationFrame(() =>
        requestAnimationFrame(() =>
          root.removeAttribute("data-theme-switching"),
        ),
      );
    };
    if (
      !prefersReducedMotion() &&
      typeof document.startViewTransition === "function"
    ) {
      document.startViewTransition(flip);
    } else {
      flip();
    }
  }, []);

  return (
    <ThemeContext.Provider value={{ theme, toggle }}>
      {children}
    </ThemeContext.Provider>
  );
}
