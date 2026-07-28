package net.thebullpen.baseball.inference.routing;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Boot-time half of the routing invariants (renamed from {@code RoutingChampionIntegrityCheck} when
 * issue #374 widened it to both columns): every {@code model_routing} row must reference a
 * CHAMPION-stage version OF ITS OWN MODEL in the champion slot (task #94 / V020), and any OCCUPIED
 * challenger slot must reference a same-model SHADOW-stage version (issue #374 / V021). The
 * triggers and the {@link RoutingService} write-path checks gate every FUTURE write, but neither
 * validates rows already present when the process starts - a hand-edited {@code registry.sqlite},
 * or a row stranded by a stage flip that predates these guards.
 *
 * <p>{@link SmartInitializingSingleton} rather than {@code ApplicationRunner} is deliberate: {@code
 * afterSingletonsInstantiated} fires at the end of singleton pre-instantiation, BEFORE the
 * lifecycle start phase brings the web server up, so a violating row fails the boot without ever
 * accepting a request. An {@code ApplicationRunner} would leave a window where the router could
 * serve off the corrupt row before the runner killed the process.
 *
 * <p>FAIL-HARD IS DELIBERATE, and worth stating because it trades availability for integrity: the
 * alternative to refusing to start is the router serving (or shadow-routing) a version that never
 * passed the promotion gates, which is strictly worse under this project's refuse-loudly posture
 * (same call as the prior-snapshot staleness refusal and the schema-hash hard fail). Note what this
 * check CANNOT be recovered through: any admin endpoint (the api hosting them is the process
 * refusing to boot) or {@code transitionStage} (a stranded row's version is typically ARCHIVED,
 * which is terminal). Recovery is {@code docs/runbooks/registry-routing-integrity.md}: an
 * out-of-band {@code sqlite3} repair on the box (with a {@code .backup} first - the documented
 * emergency exception to ADR-0006's read-only rule; a champion violation deletes the row, a
 * challenger violation clears the slot), or restoring {@code registry.sqlite} from the nightly
 * snapshot's {@code _sqlite/} capture. Never weaken the check itself.
 *
 * <p>Not profile-restricted: the api serves through the router and the worker's live predictor
 * routes too, so both processes must refuse to start on a corrupt table.
 */
@Component
public class RoutingIntegrityCheck implements SmartInitializingSingleton {

  private static final Logger log = LoggerFactory.getLogger(RoutingIntegrityCheck.class);

  private final RoutingRepository repo;

  public RoutingIntegrityCheck(RoutingRepository repo) {
    this.repo = repo;
  }

  @Override
  public void afterSingletonsInstantiated() {
    // One read: the checked-count and both violation scans come from the same rows, so the log
    // never reports a count from a different table state than the one that was checked.
    List<RoutingRepository.RoutingStageRow> rows = repo.findRoutingStageRows();
    List<RoutingRepository.RoutingStageRow> violations =
        rows.stream().filter(r -> r.championViolates() || r.challengerViolates()).toList();
    if (!violations.isEmpty()) {
      throw new IllegalStateException(
          "routing integrity: "
              + violations.size()
              + " model_routing row(s) violate the stage invariants (champion must be a"
              + " same-model CHAMPION, an occupied challenger slot a same-model SHADOW) -"
              + " refusing to start rather than serve outside the promotion gates"
              + " (task #94 / issue #374): "
              + violations);
    }
    log.info(
        "routing integrity: model_routing stage invariants hold ({} row(s) checked)", rows.size());
  }
}
