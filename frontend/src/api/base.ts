/**
 * Single source of the API base URL (FE-C1).
 *
 * In dev / test it defaults to the local backend. In a PRODUCTION build {@code VITE_API_BASE} MUST be
 * set: we refuse to silently fall back to {@code http://localhost:8080}, which would make every API
 * call quietly fail against a server that isn't there. A misconfigured prod bundle fails fast and
 * loud at load instead of shipping a dead app. See frontend/.env.example.
 */
function resolveApiBase(): string {
  const configured = import.meta.env.VITE_API_BASE;
  if (configured) {
    return configured;
  }
  if (import.meta.env.PROD) {
    throw new Error(
      "VITE_API_BASE is not set. A production build must point at the real API; refusing to fall " +
        "back to http://localhost:8080. Set VITE_API_BASE (see frontend/.env.example).",
    );
  }
  return "http://localhost:8080";
}

export const API_BASE = resolveApiBase();
