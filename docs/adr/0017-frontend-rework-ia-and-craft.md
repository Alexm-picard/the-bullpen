# ADR-0017: Frontend rework - information architecture and craft standard

- **Status**: Accepted
- **Date**: 2026-08-09
- **Deciders**: alex
- **Related**: `decisions.md` entry [191], `docs/design/frontend-ia-audit.md` (PR #437), decision [160] (broadcast identity)

## Context

The frontend reached its current state through incremental feature shipping across Phases 1-5. Each page and panel was built when its backend landed, in shipping order rather than user-journey order. The result:

1. **Navigation reflects build order, not audience.** PARKS (fixture-heavy) before GAMES (the live engine) before OPS (the engineering depth). A recruiter hitting the site cold reaches the weakest surface before the strongest.

2. **The game page accreted four model panels of equal visual weight** (next-pitch, pitch-type, batted-ball, pitch-log) across four separate PRs. During an at-bat only two are primary; after a BIP only one is. The layout ignores these temporal roles.

3. **Model explanatory content is scattered.** Each panel carries its own methodology caption, and the /about page is a dense fixture page that neither the recruiter (too long) nor the fan (irrelevant) nor the operator (not actionable) uses well.

4. **The light field ground (decision [160]) reads as unfinished** against the broadcast identity's chrome palette. The chrome-dark treatment was always available in the token family but unused outside the LowerThird and nav.

The IA audit (PR #437, TD-verified) diagnosed these systematically across three audiences and proposed options for each. This ADR locks the chosen options.

## Decision

We rework the frontend's information architecture, visual ground, and craft standard as follows. The broadcast identity ([160]: Barlow Condensed / Inter / JetBrains Mono, token layer, angled-chip language) is locked and stays. This rework evolves within it.

### 1. Dark broadcast field

The site's ground becomes the chrome palette: field `#080f1f`/`#0e1b33`, panels `#101d38`. This is a [160]-consistent arrangement of the existing token family (chrome promoted to ground), NOT a new identity and NOT a dark-mode toggle. The rejected "dark mode v1" decision was about a user-facing light/dark toggle; this is a single committed look that uses the broadcast-dark values the token layer already carries.

### 2. Navigation: journey order + Models group

Primary: HOME | GAMES | PLAYERS. Models group: GUIDE | PARKS | ACCURACY. Far right: OPS | ABOUT. The gold location underline signals the active page. This puts the live surfaces first (what a visitor uses), model depth second (what a recruiter evaluates), and operations last (what the operator checks).

### 3. Game-page temporal hierarchy

Next-pitch is primary during an at-bat (big-number read, gold-ring panel). Pitch-type is secondary alongside a compact 3-pitch log (expandable to 50). When a ball is in play, the batted-ball panel enters at the top as primary (entrance animation, reduced-motion honored), and the prediction panels move to secondary. The pitch log is always accessible but compact by default.

### 4. Model Guide page (`/models/guide`)

New page, justified against the "no new pages" constraint: it absorbs every per-panel explanatory caption sitewide. Data surfaces show the game; one page does the explaining. Each panel links to its Guide anchor via a WHAT/WHY component. The Guide uses the light "paper" palette to visually distinguish reading-surface from broadcast-surface. Anchors: `#next-pitch`, `#pitch-type`, `#batted-ball`, `#how-scored`.

### 5. About demoted to slim colophon

About moves to the nav's far-right slot. Its content slims to provenance ("how this was built"), the stack table, and the rejected-alternatives tag cloud. All methodology explanations that were on /about move to the Guide. The facts ribbon updates to live-derived counts where endpoints exist, else carries `SHELF:` markers per [190].

### 6. Home as portfolio landing

The home page leads with the display-scale "Tonight's Slate" treatment, an identity line, the fleet-status chips, and a Guide link. The game-card grid fills the body. A recruiter sees what the project is and that real models serve; a fan sees tonight's games.

## Consequences

**Easier:** A recruiter finds the engineering depth (Models group, Ops) by the second click. A fan finds the game page from the home slate and sees a hierarchical, temporal layout instead of four equal panels. The operator's daily check is unchanged (Ops structure stays). Explanatory content lives in one maintained page rather than N panel captions that drift independently.

**Harder:** The dark field commits us to a single look. Any future light-mode request is a larger change (would need the toggle that was rejected). The Guide page adds a maintenance surface (caption text must be updated when model behavior changes). The temporal panel hierarchy on the game page is more complex than a static stack.

**New failure modes:** D4 honesty labels must survive relocation. A "showcase data" caption that was inline on a panel must not move behind a link, because the link might not be followed. Only methodology explanations move to the Guide; honesty disclosures stay inline. The dark field's muted text color (`#7c8699` on `#101d38`) is near the AA contrast floor for small text and must be verified per PR.

**Locked into:** The dark broadcast field as the committed look. The Guide page as the canonical location for methodology. The journey-order navigation. Each is reversible via a future decision but carries migration cost.

## Alternatives Considered

### Alternative A: Light-field restyle only (no IA changes)

- Restyle within the current light ground, keeping the same navigation order and page structure.
- Rejected: the audit's findings are structural (navigation order, panel hierarchy, scattered explanations), not purely visual. A restyle that does not reorganize information addresses the symptom (looks unfinished) but not the cause (built in shipping order).

### Alternative B: Dark/light user toggle

- Add a `prefers-color-scheme` toggle or a manual switch, maintaining both palettes.
- Rejected: decision [160]'s "dark mode v1" rejection still holds. A toggle doubles the design surface (every component in two looks), and this is a solo project. The broadcast identity is deliberately a single committed look, matching broadcast-graphics precedent (ESPN, MLB Network do not offer light/dark modes on their broadcast overlays).

### Alternative C: Keep About in primary nav, expand it

- Rewrite /about as a scannable "60-second resume" page instead of demoting it.
- Rejected: the Guide absorbs the methodology content that gave /about its length, and a slim colophon (provenance, stack, rejected alternatives) does not justify a primary nav slot. A recruiter finds the Guide via the Models group; the colophon is a footer-grade surface.

### Alternative D: Tab-based game page panels

- Group the four model panels behind tabs (Predictions | Pitch Log | Batted Ball).
- Rejected: tabs hide content a fan might want to glance at without clicking. The temporal hierarchy (primary/secondary with state-driven elevation) keeps everything visible while signaling what matters now.

## Revision History

(none yet)
