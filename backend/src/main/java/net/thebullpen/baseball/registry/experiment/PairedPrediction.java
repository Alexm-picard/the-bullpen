package net.thebullpen.baseball.registry.experiment;

/**
 * One champion-and-challenger paired prediction with observed truth — the row shape {@link
 * MetricsComputer} operates on. Constructed by {@link PairedPredictionFetcher} which joins the
 * {@code prediction_log} CHAMPION + SHADOW rows for the same {@code request_id} and adds the
 * observed outcome (5-class pitch outcome OR binary HR for batted-ball).
 *
 * <p>{@code championProbs} and {@code challengerProbs} are full distributions over the K outcome
 * classes. {@code truthClass} is the integer index into those distributions matching the observed
 * event.
 */
public record PairedPrediction(
    long requestId, double[] championProbs, double[] challengerProbs, int truthClass) {

  public PairedPrediction {
    if (championProbs == null || challengerProbs == null) {
      throw new IllegalArgumentException("probability arrays must not be null");
    }
    if (championProbs.length != challengerProbs.length) {
      throw new IllegalArgumentException(
          "champion and challenger probability vectors must have same length; got "
              + championProbs.length
              + " and "
              + challengerProbs.length);
    }
    if (truthClass < 0 || truthClass >= championProbs.length) {
      throw new IllegalArgumentException(
          "truthClass " + truthClass + " out of range [0, " + championProbs.length + ")");
    }
  }
}
