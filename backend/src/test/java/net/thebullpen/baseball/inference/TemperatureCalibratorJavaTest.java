package net.thebullpen.baseball.inference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for the pitch-TYPE temperature calibrator (decision [183]).
 *
 * <p>The expected values are NOT hand-derived: they were produced by the Python implementation
 * ({@code bullpen_training.pitch_type.temperature.TemperatureCalibrator}) and pasted here at full
 * double precision, so these are genuine cross-language parity assertions. If the two ever diverge,
 * the model serves different numbers than it was calibrated to produce - and because decision
 * [183]'s gate is absolute calibration, a serving/training calibration drift would invalidate the
 * very metric the model is promoted on.
 */
class TemperatureCalibratorJavaTest {

  @TempDir Path tmp;

  private static final String[] LABELS = {"FF", "SI", "FC", "SL", "CU", "CH", "OFF"};
  private static final double[] RAW = {0.40, 0.20, 0.15, 0.10, 0.07, 0.05, 0.03};

  private Path writeCalibrator(String json) throws IOException {
    Path p = tmp.resolve("calibrator.json");
    Files.writeString(p, json);
    return p;
  }

  private static String calibratorJson(double temperature) {
    StringBuilder sb = new StringBuilder("{\"kind\":\"temperature\",\"class_labels\":[");
    for (int i = 0; i < LABELS.length; i++) {
      if (i > 0) {
        sb.append(",");
      }
      sb.append("\"").append(LABELS[i]).append("\"");
    }
    return sb.append("],\"temperature\":").append(temperature).append("}").toString();
  }

  @Test
  void matches_python_for_a_flattening_temperature() throws Exception {
    // Python: TemperatureCalibrator(temperature=1.7).transform([RAW])
    double[] expected = {
      0.28247919637216945,
      0.18789274056200614,
      0.15864143385481413,
      0.12497798920876409,
      0.10132456150696273,
      0.08312986302688681,
      0.061554215468396734
    };
    double[] got =
        TemperatureCalibratorJava.load(writeCalibrator(calibratorJson(1.7))).transform(RAW);
    for (int i = 0; i < expected.length; i++) {
      assertThat(got[i]).as(LABELS[i]).isCloseTo(expected[i], within(1e-12));
    }
    assertThat(java.util.Arrays.stream(got).sum()).isCloseTo(1.0, within(1e-12));
  }

  @Test
  void matches_python_for_a_sharpening_temperature() throws Exception {
    // Python: TemperatureCalibrator(temperature=0.35).transform([RAW]). T < 1 sharpens; this is the
    // regime where a naive p^(1/T) implementation underflows, which is why Java works in log space.
    double[] expected = {
      0.8144392939993796,
      0.11240173550357734,
      0.049408901453611404,
      0.01551269718602965,
      0.005598997665861264,
      0.0021409257864865756,
      0.0004974484050538831
    };
    double[] got =
        TemperatureCalibratorJava.load(writeCalibrator(calibratorJson(0.35))).transform(RAW);
    for (int i = 0; i < expected.length; i++) {
      assertThat(got[i]).as(LABELS[i]).isCloseTo(expected[i], within(1e-12));
    }
  }

  @Test
  void matches_python_on_a_row_containing_exact_zeros() throws Exception {
    // Exercises the LOG_FLOOR clamp: log(0) would be -Infinity. Python clamps at 1e-12 and so
    // must Java, or the two diverge exactly on the rows a sparse prior produces.
    double[] expected = {
      0.4999999999974998,
      0.4999999999974998,
      9.999999999949987E-13,
      9.999999999949987E-13,
      9.999999999949987E-13,
      9.999999999949987E-13,
      9.999999999949987E-13
    };
    double[] got =
        TemperatureCalibratorJava.load(writeCalibrator(calibratorJson(1.0)))
            .transform(new double[] {0.5, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0});
    for (int i = 0; i < expected.length; i++) {
      assertThat(got[i]).as(LABELS[i]).isCloseTo(expected[i], within(1e-15));
    }
  }

  @Test
  void is_order_preserving() throws Exception {
    // THE [183] guarantee: calibration may move confidence but must never re-rank. If this fails,
    // the "calibrated prior, not a top-1 predictor" framing stops being true in the serving path.
    double[] messy = {0.05, 0.31, 0.02, 0.44, 0.11, 0.06, 0.01};
    for (double t : new double[] {0.2, 0.9, 1.0, 3.5}) {
      double[] got =
          TemperatureCalibratorJava.load(writeCalibrator(calibratorJson(t))).transform(messy);
      for (int i = 0; i < messy.length; i++) {
        for (int j = 0; j < messy.length; j++) {
          if (messy[i] > messy[j]) {
            assertThat(got[i]).as("T=%s: rank of %d vs %d", t, i, j).isGreaterThan(got[j]);
          }
        }
      }
    }
  }

  @Test
  void temperature_of_one_is_the_identity() throws Exception {
    double[] got =
        TemperatureCalibratorJava.load(writeCalibrator(calibratorJson(1.0))).transform(RAW);
    for (int i = 0; i < RAW.length; i++) {
      assertThat(got[i]).isCloseTo(RAW[i], within(1e-12));
    }
  }

  @Test
  void rejects_a_non_temperature_calibrator() throws Exception {
    // The pitch-OUTCOME heads ship isotonic calibrator.json files with the same filename. Loading
    // one here would silently mis-calibrate rather than fail, so the kind is checked.
    Path p = writeCalibrator("{\"class_labels\":[\"FF\"],\"breakpoints\":[]}");
    assertThatThrownBy(() -> TemperatureCalibratorJava.load(p))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("temperature");
  }

  @Test
  void rejects_a_non_positive_temperature() throws Exception {
    for (double bad : new double[] {0.0, -1.0}) {
      Path p = writeCalibrator(calibratorJson(bad));
      assertThatThrownBy(() -> TemperatureCalibratorJava.load(p))
          .as("T=%s must not load", bad)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("> 0");
    }
  }

  @Test
  void a_nan_row_fails_loud_rather_than_serving_garbage() throws Exception {
    // The sum>0 guard is the NaN BACKSTOP, not dead code: Math.max(NaN, floor) is NaN, NaN never
    // updates the running max, so every exp() and the sum go NaN. That is exactly what a
    // NaN-producing ONNX graph yields, and it must 422 at promote-time rather than serve a
    // garbage 7-vector as a calibrated prior.
    TemperatureCalibratorJava cal =
        TemperatureCalibratorJava.load(writeCalibrator(calibratorJson(1.0)));
    assertThatThrownBy(() -> cal.transform(new double[] {0.5, Double.NaN, 0.1, 0.1, 0.1, 0.1, 0.1}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("non-normalisable");
  }

  @Test
  void rejects_a_row_whose_width_does_not_match_the_labels() throws Exception {
    TemperatureCalibratorJava cal =
        TemperatureCalibratorJava.load(writeCalibrator(calibratorJson(1.0)));
    assertThatThrownBy(() -> cal.transform(new double[] {0.5, 0.5}))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
