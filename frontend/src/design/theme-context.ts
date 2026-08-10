import { createContext } from "react";

type Theme = "light" | "dark";

export const ThemeContext = createContext<{
  theme: Theme;
  toggle: () => void;
}>({ theme: "dark", toggle: () => {} });
