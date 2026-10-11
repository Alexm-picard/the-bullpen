/**
 * Catch-all 404 ([195] editorial identity; SPEC-games-404 §7). Without a `path="*"` route an
 * unknown URL rendered the layout shell with an empty <Outlet/>; this says plainly that the address
 * is not a page, shows what was asked for, and offers real places to go - a dead end that still
 * answers "where can I go".
 */
import { Link, useLocation } from "react-router";

import { BUILD_DATE, BUILD_SHA } from "../build-info";

import { echoPath } from "./not-found-path";

const ET_DATE = new Intl.DateTimeFormat("en-US", {
  weekday: "long",
  month: "long",
  day: "numeric",
  year: "numeric",
  timeZone: "America/New_York",
});

const DESTINATIONS: readonly { to: string; label: string; what: string }[] = [
  {
    to: "/",
    label: "Tonight",
    what: "The front page: tonight’s featured game and the next pitch, by type.",
  },
  {
    to: "/games",
    label: "Games",
    what: "Every game on today’s slate, live first.",
  },
  {
    to: "/players",
    label: "Players",
    what: "Look up a player by name or Statcast ID.",
  },
  {
    to: "/accuracy",
    label: "Accuracy",
    what: "How every model has scored against what happened.",
  },
];

export default function NotFoundPage() {
  const { pathname } = useLocation();
  return (
    <div className="ed-page">
      <div className="ed-col">
        <p className="ed-dateline">
          <span>{ET_DATE.format(new Date())}</span>
          <span className="ed-dateline__issued">Page not found</span>
          <span>Error 404</span>
        </p>

        <div className="nf">
          <div>
            <p className="ed-slug">Page not found</p>
            <h1 className="ed-h1">No play at this base.</h1>
            <p className="ed-dek">
              That page isn&rsquo;t in the lineup. The address may be mistyped,
              or the page moved when the site was redesigned.
            </p>
            <p className="nf-links">
              <Link className="ed-link" to="/">
                Back to home
              </Link>
              <Link className="ed-link" to="/games">
                See today&rsquo;s games
              </Link>
            </p>
          </div>
          <aside aria-labelledby="nf-book-h">
            <p className="ed-label" id="nf-book-h">
              In the scorebook
            </p>
            <span className="nf-code">404</span>
            <dl className="nf-book">
              <dt>Asked for</dt>
              <dd>
                <code>{echoPath(pathname)}</code>
              </dd>
              <dt>Ruling</dt>
              <dd>No play. The runner returns to the front page.</dd>
            </dl>
          </aside>
        </div>

        <nav className="nf-go" aria-labelledby="nf-go-h">
          <h2 className="ed-h3" id="nf-go-h">
            Where to go instead
          </h2>
          <ul>
            {DESTINATIONS.map((d) => (
              <li key={d.to}>
                <Link className="ed-link" to={d.to}>
                  {d.label}
                </Link>
                <p>{d.what}</p>
              </li>
            ))}
          </ul>
        </nav>

        <footer className="ed-footer">
          <span>The Bullpen. Self-hosted, honestly scored.</span>
          <span>
            build {BUILD_SHA}, {BUILD_DATE}
          </span>
        </footer>
      </div>
    </div>
  );
}
