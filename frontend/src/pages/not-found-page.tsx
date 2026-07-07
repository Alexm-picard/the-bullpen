/**
 * S6 - catch-all 404. Without a `path="*"` route, an unknown URL rendered the layout
 * shell with an empty <Outlet/> (a blank panel under the nav chrome) rather than an
 * honest not-found page. This renders a broadcast-styled 404 with a way back to the slate.
 */
import { Link } from "react-router-dom";

import { PageChrome } from "../components/shared/page-chrome";
import { colors, layouts, typography } from "../design/broadcast";

const codeStyle: React.CSSProperties = {
  fontFamily: typography.fonts.display,
  fontStyle: "italic",
  fontWeight: typography.weights.heavy,
  fontSize: typography.scale[7],
  lineHeight: typography.lineHeights.display,
  color: colors.ink,
  margin: 0,
};

const headingStyle: React.CSSProperties = {
  fontFamily: typography.fonts.display,
  fontWeight: typography.weights.semibold,
  fontSize: typography.scale[4],
  color: colors.ink,
  margin: 0,
};

const bodyStyle: React.CSSProperties = {
  fontFamily: typography.fonts.body,
  fontSize: typography.scale[2],
  color: colors.textMuted,
  margin: 0,
  maxWidth: layouts.editorialMaxWidth,
};

const linkStyle: React.CSSProperties = {
  marginTop: 8,
  fontFamily: typography.fonts.body,
  fontWeight: typography.weights.medium,
  fontSize: typography.scale[2],
  color: colors.goldInk,
  textDecoration: "none",
};

export default function NotFoundPage() {
  return (
    <PageChrome gap={12} topPad={56}>
      <p style={codeStyle}>404</p>
      <h1 style={headingStyle}>No play at this base.</h1>
      <p style={bodyStyle}>
        That page isn&rsquo;t in the lineup. Check the URL, or head back to
        tonight&rsquo;s slate.
      </p>
      <Link to="/" style={linkStyle}>
        &larr; Back to home
      </Link>
    </PageChrome>
  );
}
