package net.thebullpen.baseball.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Single-scalar temperature calibrator - the Java consumer for the pitch-TYPE head's {@code
 * calibrator.json} (decision [183]).
 *
 * <p>A NEW calibrator family for this codebase: the pitch-OUTCOME heads use per-class isotonic
 * ({@link IsotonicCalibratorJava}). Temperature was chosen for pitch-type because it is
 * ORDER-PRESERVING by construction - one positive scalar divides the logits and re-softmaxes, so
 * the argmax and the full per-row ranking are invariant. That property is load-bearing, not
 * incidental: [183] scopes this model as a calibrated pitch-type PRIOR whose value is calibration
 * (ECE &lt; 0.02), explicitly NOT a top-1 next-pitch predictor, so the calibration step must be
 * able to move confidence without ever sharpening its way into a different top pick.
 *
 * <p>Arithmetic is deliberately identical to the Python side ({@code
 * bullpen_training.pitch_type.temperature.TemperatureCalibrator}) so a Java/Python parity fixture
 * holds: clamp each probability at {@value #LOG_FLOOR}, take {@code log(p) / T}, then a
 * max-subtracting softmax. Working in log space (rather than the algebraically equivalent {@code
 * p^(1/T) / sum p^(1/T)}) is what keeps a small T from underflowing to zero across the whole row.
 *
 * <p>JSON shape: <code>{"kind":"temperature","class_labels":[...],"temperature":&lt;double&gt;}
 * </code>.
 */
public final class TemperatureCalibratorJava {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Probability floor applied before {@code log}, matching the Python {@code _LOG_FLOOR}. Keeps a
   * zero probability finite instead of {@code -Infinity}, and keeps the two implementations
   * bit-comparable.
   */
  static final double LOG_FLOOR = 1e-12;

  static final String KIND = "temperature";

  private final double temperature;
  private final List<String> classLabels;

  private TemperatureCalibratorJava(double temperature, List<String> classLabels) {
    this.temperature = temperature;
    this.classLabels = List.copyOf(classLabels);
  }

  public double temperature() {
    return temperature;
  }

  public List<String> classLabels() {
    return classLabels;
  }

  /**
   * Parse {@code calibrator.json}. Fails loud on a non-temperature {@code kind} or a non-positive
   * temperature: a strictly positive T IS the order-preservation guarantee, so a bundle carrying a
   * zero or negative temperature would divide by zero or invert the ranking. It must fail to load
   * rather than serve a silently inverted prior.
   */
  public static TemperatureCalibratorJava load(Path calibratorJson) throws IOException {
    JsonNode root = MAPPER.readTree(Files.readAllBytes(calibratorJson));
    String kind = root.path("kind").asText("");
    if (!KIND.equals(kind)) {
      throw new IllegalStateException(
          "expected calibrator kind '" + KIND + "' at " + calibratorJson + ", got '" + kind + "'");
    }
    if (!root.has("temperature")) {
      throw new IllegalStateException("calibrator at " + calibratorJson + " has no temperature");
    }
    double t = root.path("temperature").asDouble(Double.NaN);
    if (!Double.isFinite(t) || t <= 0.0) {
      throw new IllegalStateException(
          "temperature must be finite and > 0 (the order-preservation invariant) at "
              + calibratorJson
              + ", got "
              + t);
    }
    List<String> labels = new ArrayList<>();
    for (JsonNode label : root.path("class_labels")) {
      labels.add(label.asText());
    }
    if (labels.isEmpty()) {
      throw new IllegalStateException("calibrator at " + calibratorJson + " has no class_labels");
    }
    return new TemperatureCalibratorJava(t, labels);
  }

  /**
   * Apply temperature scaling to one row of raw probabilities, returning a fresh array that sums to
   * 1. Order-preserving: the returned row's ranking is identical to {@code rawProbs}.
   */
  public double[] transform(double[] rawProbs) {
    if (rawProbs.length != classLabels.size()) {
      throw new IllegalArgumentException(
          "expected "
              + classLabels.size()
              + " probabilities (one per class label), got "
              + rawProbs.length);
    }
    double[] scaled = new double[rawProbs.length];
    double max = Double.NEGATIVE_INFINITY;
    for (int i = 0; i < rawProbs.length; i++) {
      scaled[i] = Math.log(Math.max(rawProbs[i], LOG_FLOOR)) / temperature;
      if (scaled[i] > max) {
        max = scaled[i];
      }
    }
    double sum = 0.0;
    for (int i = 0; i < scaled.length; i++) {
      scaled[i] = Math.exp(scaled[i] - max);
      sum += scaled[i];
    }
    if (!(sum > 0.0) || !Double.isFinite(sum)) {
      // NOT unreachable: this is the NaN backstop. A NaN anywhere in rawProbs survives
      // Math.max(NaN, LOG_FLOOR), never updates max (NaN > x is false), and makes every
      // exp() and the sum NaN - so !(sum > 0.0) is true. That is exactly the path a
      // NaN-producing ONNX graph takes, and failing here turns it into a promote-time 422
      // instead of a garbage 7-vector served as a prior.
      throw new IllegalStateException(
          "temperature calibration produced a non-normalisable row (sum=" + sum + ")");
    }
    for (int i = 0; i < scaled.length; i++) {
      scaled[i] /= sum;
    }
    return scaled;
  }
}
