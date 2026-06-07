package net.thebullpen.baseball.registry.dto;

import java.util.Set;

/**
 * The four-stage model lifecycle from decision [65]. The matching CHECK constraint lives on {@code
 * model_versions.stage} (migration V010); this enum is the typed mirror.
 *
 * <p>Allowed transitions (decision [65] + 3a.2 leaf):
 *
 * <ul>
 *   <li>{@link #CANDIDATE} → {@link #SHADOW} (registration validated, starts logging in shadow
 *       mode)
 *   <li>{@link #CANDIDATE} → {@link #CHAMPION} (first-ever promotion for a model with no prior
 *       champion)
 *   <li>{@link #SHADOW} → {@link #CHAMPION} (normal promotion path; rule 5 + 6 — gated by a passing
 *       experiment_results row)
 *   <li>{@link #CHAMPION} → {@link #SHADOW} (controlled ROLLBACK of a bad champion; INC-1 /
 *       decision [150] — the registry charter is rollback-able change. The demote removes the
 *       routing row so the legacy fallback serves; the version stays re-promotable)
 *   <li>any → {@link #ARCHIVED} (terminal; archived rows stay forever, never re-activate)
 * </ul>
 *
 * <p>Explicitly not allowed: {@code SHADOW → CANDIDATE} (no demotion back to candidate — archive
 * then re-register instead), {@code ARCHIVED → *} (terminal).
 */
public enum Stage {
  CANDIDATE,
  SHADOW,
  CHAMPION,
  ARCHIVED;

  /** Allowed targets when leaving this stage. ARCHIVED is reachable from any stage. */
  public Set<Stage> allowedTargets() {
    return switch (this) {
      case CANDIDATE -> Set.of(SHADOW, CHAMPION, ARCHIVED);
      case SHADOW -> Set.of(CHAMPION, ARCHIVED);
      case CHAMPION ->
          Set.of(SHADOW, ARCHIVED); // SHADOW = INC-1 controlled rollback (decision [150])
      case ARCHIVED -> Set.of(); // terminal
    };
  }

  public boolean canTransitionTo(Stage target) {
    return allowedTargets().contains(target);
  }

  /** Database-side lowercase string ({@code 'candidate' | 'shadow' | 'champion' | 'archived'}). */
  public String dbValue() {
    return name().toLowerCase();
  }

  public static Stage fromDbValue(String s) {
    return Stage.valueOf(s.toUpperCase());
  }
}
