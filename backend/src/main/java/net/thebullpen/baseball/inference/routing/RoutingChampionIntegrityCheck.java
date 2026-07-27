package net.thebullpen.baseball.inference.routing;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Boot-time half of the task-#94 invariant: every {@code model_routing} row must reference a
 * CHAMPION-stage version. The V020 triggers and the {@link RoutingService} write-path checks gate
 * every FUTURE write, but neither validates rows already present when the process starts - a
 * hand-edited {@code registry.sqlite}, or a row stranded by a stage flip that predates this guard.
 *
 * <p>{@link SmartInitializingSingleton} rather than {@code ApplicationRunner} is deliberate: {@code
 * afterSingletonsInstantiated} fires at the end of singleton pre-instantiation, BEFORE the
 * lifecycle start phase brings the web server up, so a violating row fails the boot without ever
 * accepting a request. An {@code ApplicationRunner} would leave a window where the router could
 * serve off the corrupt row before the runner killed the process.
 *
 * <p>FAIL-HARD IS DELIBERATE, and worth stating because it trades availability for integrity: the
 * alternative to refusing to start is the router silently serving a version that never passed the
 * rule-5/rule-6 promotion gates, which is strictly worse under this project's refuse-loudly posture
 * (same call as the prior-snapshot staleness refusal and the schema-hash hard fail). Recovery is
 * not "weaken this check": fix the row by promoting a legitimate champion, removing the routing
 * row, or restoring {@code registry.sqlite} per the registry-snapshot-recovery runbook.
 *
 * <p>Not profile-restricted: the api serves through the router and the worker's live predictor
 * routes too, so both processes must refuse to start on a corrupt table.
 */
@Component
public class RoutingChampionIntegrityCheck implements SmartInitializingSingleton {

  private static final Logger log = LoggerFactory.getLogger(RoutingChampionIntegrityCheck.class);

  private final RoutingRepository repo;

  public RoutingChampionIntegrityCheck(RoutingRepository repo) {
    this.repo = repo;
  }

  @Override
  public void afterSingletonsInstantiated() {
    List<RoutingRepository.ChampionStageViolation> violations = repo.findChampionStageViolations();
    if (!violations.isEmpty()) {
      throw new IllegalStateException(
          "routing integrity: "
              + violations.size()
              + " model_routing row(s) reference a non-CHAMPION version - refusing to start"
              + " rather than serve outside the promotion gates (task #94): "
              + violations);
    }
    log.info(
        "routing integrity: model_routing champion-stage invariant holds ({} row(s) checked)",
        repo.findAll().size());
  }
}
