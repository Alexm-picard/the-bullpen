package net.thebullpen.baseball.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The 30-park x 5-outcome isotonic calibrators for one batted-ball model (B-workstream B3).
 *
 * <p>Loads the merged {@code calibrator.json} an exporter writes ({@code park_order}, {@code
 * outcome_order}, and {@code parks: {park -> [per-outcome IsotonicCalibrator]}}). Calibration is
 * applied POST-inference to the raw softmax: per outcome run the park's {@link IsotonicCalibrator},
 * floor at {@code 1e-9}, then renormalize so the distribution sums to 1 - the same {@code
 * np.maximum(.,1e-9)} / divide-by-sum the Python serving path applies, so a Java prediction matches
 * the exporter's calibrated output (B5 parity).
 */
public final class BattedBallCalibrators {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final double FLOOR = 1e-9;

  private final List<String> parkOrder;
  private final List<String> outcomeOrder;
  private final Map<String, IsotonicCalibrator[]> byPark;

  private BattedBallCalibrators(
      List<String> parkOrder, List<String> outcomeOrder, Map<String, IsotonicCalibrator[]> byPark) {
    this.parkOrder = parkOrder;
    this.outcomeOrder = outcomeOrder;
    this.byPark = byPark;
  }

  public static BattedBallCalibrators load(Path calibratorJson) throws IOException {
    JsonNode root = MAPPER.readTree(Files.readAllBytes(calibratorJson));
    List<String> parkOrder = new ArrayList<>();
    root.get("park_order").forEach(n -> parkOrder.add(n.asText()));
    List<String> outcomeOrder = new ArrayList<>();
    root.get("outcome_order").forEach(n -> outcomeOrder.add(n.asText()));
    int nOutcomes = outcomeOrder.size();

    JsonNode parks = root.get("parks");
    Map<String, IsotonicCalibrator[]> byPark = new HashMap<>();
    for (String park : parkOrder) {
      JsonNode classes = parks.get(park);
      if (classes == null || classes.size() != nOutcomes) {
        throw new IllegalStateException(
            "park "
                + park
                + " has "
                + (classes == null ? 0 : classes.size())
                + " calibrators, expected "
                + nOutcomes);
      }
      IsotonicCalibrator[] cals = new IsotonicCalibrator[nOutcomes];
      for (int o = 0; o < nOutcomes; o++) {
        JsonNode c = classes.get(o);
        cals[o] =
            new IsotonicCalibrator(
                readDoubleArray(c.get("x_thresholds")), readDoubleArray(c.get("y_thresholds")));
      }
      byPark.put(park, cals);
    }
    return new BattedBallCalibrators(
        List.copyOf(parkOrder), List.copyOf(outcomeOrder), Map.copyOf(byPark));
  }

  public List<String> parkOrder() {
    return parkOrder;
  }

  public List<String> outcomeOrder() {
    return outcomeOrder;
  }

  /** Index of {@code parkId} in the ONNX park axis, or -1 if the model doesn't cover it. */
  public int parkIndex(String parkId) {
    return parkOrder.indexOf(parkId);
  }

  /**
   * Apply {@code parkId}'s per-outcome isotonic calibrators to its raw softmax, floor +
   * renormalize.
   */
  public float[] calibrate(String parkId, float[] rawSoftmax) {
    IsotonicCalibrator[] cals = byPark.get(parkId);
    if (cals == null) {
      throw new IllegalArgumentException("no calibrators for park " + parkId);
    }
    if (rawSoftmax.length != cals.length) {
      throw new IllegalArgumentException(
          "raw softmax length " + rawSoftmax.length + " != outcomes " + cals.length);
    }
    double[] cal = new double[rawSoftmax.length];
    double sum = 0.0;
    for (int o = 0; o < rawSoftmax.length; o++) {
      cal[o] = Math.max(cals[o].transform(rawSoftmax[o]), FLOOR);
      sum += cal[o];
    }
    float[] out = new float[rawSoftmax.length];
    for (int o = 0; o < out.length; o++) {
      out[o] = (float) (cal[o] / sum);
    }
    return out;
  }

  private static double[] readDoubleArray(JsonNode node) {
    if (node == null || !node.isArray()) {
      throw new IllegalStateException("expected a numeric array in calibrator.json");
    }
    double[] out = new double[node.size()];
    for (int i = 0; i < node.size(); i++) {
      out[i] = node.get(i).asDouble();
    }
    return out;
  }
}
