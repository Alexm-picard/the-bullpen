package net.thebullpen.baseball.registry.experiment;

import java.util.List;
import net.thebullpen.baseball.registry.experiment.dto.PrimaryMetric;

/**
 * Pure-function metric math for the experiment evaluator (3b.4). Operates on a list of {@link
 * PairedPrediction} (champion + challenger probability vectors + observed truth class) and returns
 * the metric value the leaf's threshold check compares against.
 *
 * <p>All metrics are "lower is better" — the experiment's "challenger wins" rule is {@code
 * challengerMetric + threshold <= championMetric}, with the threshold sign chosen to mean
 * "challenger must beat champion by at least this much."
 *
 * <p>{@link PrimaryMetric#ECE} uses the standard 10-bin equal-width binning over the predicted
 * probability of the true class (multi-class ECE collapsed to "max-prob bin" — the simplest
 * variant; per-class ECE bands are 3c's drift-detection job). The default 10 bins is the literature
 * standard (Guo et al. 2017 "On Calibration of Modern Neural Networks").
 */
public final class MetricsComputer {

  private static final int ECE_BINS = 10;

  private MetricsComputer() {}

  public static double compute(
      PrimaryMetric metric, List<PairedPrediction> pairs, boolean useChallenger) {
    if (pairs.isEmpty()) {
      throw new IllegalArgumentException("MetricsComputer: cannot compute on empty paired list");
    }
    return switch (metric) {
      case BRIER -> brier(pairs, useChallenger);
      case LOG_LOSS -> logLoss(pairs, useChallenger);
      case ECE -> ece(pairs, useChallenger);
    };
  }

  /**
   * Multi-class Brier score: mean squared error of the predicted probability vector vs the one-hot
   * truth vector, averaged over rows AND classes. Range [0, 2] for K-class with one-hot; 0 =
   * perfect.
   */
  static double brier(List<PairedPrediction> pairs, boolean useChallenger) {
    double sumSq = 0.0;
    long count = 0;
    for (PairedPrediction p : pairs) {
      double[] probs = useChallenger ? p.challengerProbs() : p.championProbs();
      int truth = p.truthClass();
      for (int k = 0; k < probs.length; k++) {
        double y = k == truth ? 1.0 : 0.0;
        double diff = probs[k] - y;
        sumSq += diff * diff;
      }
      count += probs.length;
    }
    return sumSq / count;
  }

  /**
   * Multinomial log-loss: -mean(log(p_truth)) clamped at {@code 1e-15} to avoid -infinity on
   * exact-zero predictions. Range [0, ~34.5] at the clamp; 0 = perfect.
   */
  static double logLoss(List<PairedPrediction> pairs, boolean useChallenger) {
    double sumNegLog = 0.0;
    for (PairedPrediction p : pairs) {
      double[] probs = useChallenger ? p.challengerProbs() : p.championProbs();
      double pTruth = Math.max(probs[p.truthClass()], 1e-15);
      sumNegLog += -Math.log(pTruth);
    }
    return sumNegLog / pairs.size();
  }

  /**
   * Expected Calibration Error (10 equal-width bins). For each row we take the predicted
   * probability of the predicted class (argmax) AND whether the prediction was correct, bin by the
   * predicted-confidence, then for each bin compute |avg_confidence - accuracy| weighted by bin
   * population.
   */
  static double ece(List<PairedPrediction> pairs, boolean useChallenger) {
    long[] binCount = new long[ECE_BINS];
    double[] binConfSum = new double[ECE_BINS];
    long[] binCorrect = new long[ECE_BINS];
    for (PairedPrediction p : pairs) {
      double[] probs = useChallenger ? p.challengerProbs() : p.championProbs();
      int predClass = argmax(probs);
      double conf = probs[predClass];
      int bin = (int) Math.min(ECE_BINS - 1, Math.floor(conf * ECE_BINS));
      binCount[bin]++;
      binConfSum[bin] += conf;
      if (predClass == p.truthClass()) {
        binCorrect[bin]++;
      }
    }
    double weighted = 0.0;
    long total = pairs.size();
    for (int b = 0; b < ECE_BINS; b++) {
      if (binCount[b] == 0) {
        continue;
      }
      double avgConf = binConfSum[b] / binCount[b];
      double accuracy = (double) binCorrect[b] / binCount[b];
      weighted += ((double) binCount[b] / total) * Math.abs(avgConf - accuracy);
    }
    return weighted;
  }

  static int argmax(double[] probs) {
    int best = 0;
    double bestVal = probs[0];
    for (int i = 1; i < probs.length; i++) {
      if (probs[i] > bestVal) {
        best = i;
        bestVal = probs[i];
      }
    }
    return best;
  }
}
