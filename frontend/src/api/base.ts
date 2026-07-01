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

/**
 * A fetch that resolved but the backend returned a non-2xx (or a client-side "not found"). Every
 * api/ module used to declare an identical `class XApiError extends Error { status }`; they now all
 * extend this one shape. Each module still exports its own thin subclass so callers and tests can
 * `instanceof` the module's error - `new.target.name` keeps the subclass name on the instance.
 */
export class ApiError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.name = new.target.name;
    this.status = status;
  }
}

/**
 * The GET-then-parse-JSON path shared by most api/ modules: fetch `${API_BASE}${path}`, throw the
 * module's error (via `makeError`, so the module's subclass type is preserved) on a non-2xx, else
 * parse the body as T. Modules with non-standard handling (a bespoke 404 message, a POST/DELETE
 * whose message is read from the response body) keep their own logic but still throw an ApiError
 * subclass.
 */
export async function apiGet<T>(
  path: string,
  makeError: (status: number, message: string) => ApiError,
): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`);
  if (!res.ok) {
    throw makeError(res.status, `${path} failed: HTTP ${res.status}`);
  }
  return (await res.json()) as T;
}
