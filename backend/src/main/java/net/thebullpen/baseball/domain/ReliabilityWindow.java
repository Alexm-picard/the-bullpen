package net.thebullpen.baseball.domain;

import java.util.List;

/**
 * The cells behind one model's live calibration chart, plus WHICH window they cover.
 *
 * <p>{@link Kind#CALENDAR}: the trailing N ET calendar days through yesterday (the in-season read).
 * {@link Kind#LAST_DAYS_OF_PLAY}: the calendar window held no graded call (the offseason, or a long
 * break), so the rows are the most recent N ET game days that DO carry graded calls. That is a
 * different claim from "the last N days", which is why the kind travels with the rows instead of
 * the fallback being silent.
 */
public record ReliabilityWindow(Kind kind, List<ReliabilityBinRow> rows) {

  public ReliabilityWindow {
    rows = List.copyOf(rows);
  }

  /** Which window the rows cover. */
  public enum Kind {
    CALENDAR,
    LAST_DAYS_OF_PLAY
  }
}
