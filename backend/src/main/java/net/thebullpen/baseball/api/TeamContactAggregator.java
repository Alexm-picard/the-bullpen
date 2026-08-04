package net.thebullpen.baseball.api;

import io.micrometer.core.instrument.Timer;
import java.time.LocalDate;
import java.util.List;
import net.thebullpen.baseball.data.LivePitchesRepository;
import net.thebullpen.baseball.domain.TeamContactBall;
import net.thebullpen.baseball.inference.FeaturePipelineBattedBall;
import net.thebullpen.baseball.inference.InferenceMetrics;
import net.thebullpen.baseball.inference.LoadedAllParksModel;
import net.thebullpen.baseball.inference.ModelLoader;
import net.thebullpen.baseball.registry.RegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Scores a team's REAL recent batted balls at one park and reports the mean P(HR) with its n.
 *
 * <p>This is the game page's answer before any ball has been put in play. It is NOT a prediction
 * anyone requested, and per decision [188] / ADR-0016 it writes NOTHING to {@code prediction_log}:
 * a couple of hundred evaluations collapse into one displayed number, so the individuals are
 * intermediates. Logging them would not merely add volume - it would redefine the population the
 * drift PSI, promotion-evidence and truth-join consumers all measure, turning the batted-ball PSI
 * lane into a measure of pregame page views.
 *
 * <p>That is why it goes through ModelLoader and RegistryService.findChampion directly rather than
 * through the prediction orchestrator or the A/B router. The orchestrator is the logging path. The
 * router additionally fans out shadow legs, which would double the inference count for legs nobody
 * consumes and which exist to accompany a SERVED input. Same model, same weights, different
 * obligations.
 *
 * <p>Bypassing the log must not trade pollution for invisibility, so every call is timed and
 * counted under the aggregate metric names ({@code thebullpen_inference_aggregate_*}), never the
 * served histogram.
 *
 * <p>NO SYNTHETIC BALL is constructed. Taking a team's median launch speed and median launch angle
 * would produce a point that never occurred - the median of marginals is not an observation - and
 * feeding it to the model would be {@code sprayAngleDeg: 0} dressed as statistics while reading as
 * authoritative. Every input here is a ball someone actually hit; the AGGREGATE is the derived
 * quantity, which is what an aggregate should be.
 */
@Component
// Same ClickHouse gate as GameController and LivePitchesRepository, which this depends on. Without
// it the bean is unsatisfiable in every ClickHouse-free profile (registry-it and friends) and the
// whole application context fails to load - which is exactly what happened on the first attempt.
@ConditionalOnProperty(name = "bullpen.clickhouse.enabled", havingValue = "true")
public class TeamContactAggregator {

  private static final Logger log = LoggerFactory.getLogger(TeamContactAggregator.class);

  /** The registry name of the batted-ball family. Matches PredictAllParksController's. */
  static final String MODEL_NAME = "battedball_outcome";

  /** The outcome slot the card reports, resolved by NAME rather than position. */
  private static final String HR_OUTCOME = "hr";

  /**
   * Base/out state for the profile. Held CONSTANT across both teams on purpose: the comparison is
   * about contact quality at this park, so letting it vary would fold an unrelated dimension into a
   * difference the card presents as a batting-profile difference. Stated in the card's copy.
   */
  private static final int PROFILE_BASE_STATE = 0;

  private static final int PROFILE_OUTS = 0;

  private final LivePitchesRepository repo;
  private final RegistryService registry;
  private final ModelLoader loader;
  private final InferenceMetrics metrics;

  public TeamContactAggregator(
      LivePitchesRepository repo,
      RegistryService registry,
      ModelLoader loader,
      InferenceMetrics metrics) {
    this.repo = repo;
    this.registry = registry;
    this.loader = loader;
    this.metrics = metrics;
  }

  /**
   * Mean P(HR) for a team's recent contact at {@code parkId}, or null when it cannot be computed
   * honestly - no champion, or no qualifying balls.
   *
   * <p>Null rather than 0.0: a team with no profileable contact has no answer, and 0.0 would read
   * as "this team never homers" on a card whose entire job is being truthful about what it knows.
   */
  public TeamHrProfile profile(String team, String parkId, LocalDate from, int limit) {
    var champion = registry.findChampion(MODEL_NAME);
    if (champion.isEmpty()) {
      return null;
    }
    List<TeamContactBall> balls = repo.findTeamContact(team, from, limit);
    if (balls.isEmpty()) {
      return null;
    }
    Timer.Sample sample = Timer.start();
    try {
      LoadedAllParksModel model = loader.loadAllParks(champion.get().id());
      // Resolve the HR slot by NAME from the serving model's own outcome order, exactly as
      // PredictAllParksController does - a positional index would silently read a different
      // outcome if the model's order ever changed.
      int hrIdx = model.outcomeOrder().indexOf(HR_OUTCOME);
      if (hrIdx < 0) {
        throw new IllegalStateException(
            "serving model outcome order has no '" + HR_OUTCOME + "': " + model.outcomeOrder());
      }
      double total = 0;
      int scored = 0;
      for (TeamContactBall b : balls) {
        var req =
            new FeaturePipelineBattedBall.Request(
                b.launchSpeedMph(),
                b.launchAngleDeg(),
                b.sprayAngleDeg(),
                b.hitDistanceFt(),
                b.stand(),
                PROFILE_BASE_STATE,
                PROFILE_OUTS);
        float[] atPark = model.predict(req).get(parkId);
        if (atPark == null) {
          continue; // the model does not serve this park; excluded rather than defaulted
        }
        total += atPark[hrIdx];
        scored++;
      }
      metrics.incrementAggregateEvaluations(MODEL_NAME, scored);
      if (scored == 0) {
        return null;
      }
      return new TeamHrProfile(team, parkId, total / scored, scored, champion.get().version());
    } catch (Exception e) {
      // Degrades to "no profile" rather than failing the page: this is a fallback panel, and a
      // pregame page that 500s because an aggregate could not be computed is a worse outcome than
      // one that says it has no comparison yet. The error counter is what makes it visible.
      metrics.incrementAggregateErrors(MODEL_NAME);
      log.warn("team-contact aggregate failed for team={} park={}", team, parkId, e);
      return null;
    } finally {
      metrics.recordAggregateLatency(sample, MODEL_NAME);
    }
  }

  /**
   * @param meanHrProbability mean P(HR) over {@code n} REAL batted balls, scored at this park.
   * @param n the number of balls behind the mean - surfaced because a mean over 8 and a mean over
   *     800 are not comparable, the same reason priorPitches is surfaced on the pitch-type panel.
   */
  public record TeamHrProfile(
      String team, String parkId, double meanHrProbability, int n, String modelVersion) {}
}
