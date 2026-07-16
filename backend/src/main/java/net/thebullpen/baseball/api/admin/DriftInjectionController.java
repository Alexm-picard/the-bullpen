package net.thebullpen.baseball.api.admin;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import net.thebullpen.baseball.api.admin.dto.DriftInjectionRequest;
import net.thebullpen.baseball.drift.DriftInjectionService;
import net.thebullpen.baseball.drift.DriftInjectionService.DriftInjectionException;
import net.thebullpen.baseball.drift.DriftInjectionService.InjectionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin surface for the E-2 live-path induced-drift drill ([175]). Gated by HTTP Basic via {@code
 * SecurityConfig} (the {@code /v1/admin/**} matcher requires role {@code ADMIN}) AND by {@code
 * bullpen.drift.inject.enabled} - this controller AND {@link DriftInjectionService} both carry the
 * same {@code @ConditionalOnProperty}, so when disabled neither bean is created and the paths 404
 * (the controller must be gated in lockstep: it depends on the service bean, so an ungated
 * controller would fail context startup whenever the property is off). The box flips the property
 * on only for the drill window, then off.
 *
 * <ul>
 *   <li>{@code POST /v1/admin/drift/induce} - write N synthetic {@code prediction_log} rows for the
 *       champion with one feature shifted, so the real 2 AM PsiFeatureJob detects drift end-to-end.
 *       Refuses (400) unless {@code bullpen.drift.tag} is armed ([175] hygiene) and the champion
 *       has a feature_distributions baseline.
 *   <li>{@code DELETE /v1/admin/drift/synthetic} - cleanup: delete every {@code drill:}-prefixed
 *       prediction_log row. Idempotent; run after the postmortem.
 * </ul>
 *
 * <p>{@link DriftInjectionException} (caller-fixable: tag disarmed, no champion, no baseline, bad
 * params) maps to 400; every synthetic row is {@code drill:}-prefixed and thus always cleanable.
 */
@RestController
@RequestMapping("/v1/admin/drift")
@Profile("api")
@ConditionalOnProperty(name = "bullpen.drift.inject.enabled", havingValue = "true")
@Tag(name = "admin-drift", description = "Live-path induced-drift drill (E-2, decision [175])")
public class DriftInjectionController {

  private static final Logger log = LoggerFactory.getLogger(DriftInjectionController.class);

  private static final String DEFAULT_MODEL = "battedball_outcome";
  private static final int DEFAULT_N = 5000;
  private static final double DEFAULT_SHIFT_SIGMAS = 1.0;
  private static final int DEFAULT_LOOKBACK_HOURS = 20;

  private final DriftInjectionService service;

  public DriftInjectionController(DriftInjectionService service) {
    this.service = service;
  }

  @PostMapping("/induce")
  public InjectionResult induce(@Valid @RequestBody(required = false) DriftInjectionRequest body) {
    DriftInjectionRequest req = body == null ? emptyRequest() : body;
    try {
      InjectionResult result =
          service.induce(
              req.modelNameOr(DEFAULT_MODEL),
              req.nOr(DEFAULT_N),
              req.shiftSigmasOr(DEFAULT_SHIFT_SIGMAS),
              req.lookbackHoursOr(DEFAULT_LOOKBACK_HOURS),
              req.shiftFeatureOr(DriftInjectionService.DEFAULT_SHIFT_FEATURE));
      log.info(
          "drift induce: {} rows for {} v{} (id={}), shifted {} by {} sigma",
          result.rowsWritten(),
          result.modelName(),
          result.modelVersion(),
          result.modelVersionId(),
          result.shiftFeature(),
          result.shiftSigmas());
      return result;
    } catch (DriftInjectionException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    }
  }

  @DeleteMapping("/synthetic")
  public Map<String, Object> cleanup() {
    long removed = service.cleanup();
    return Map.of(
        "deletedRows",
        removed,
        "note",
        "async ClickHouse mutation issued for all drill:-prefixed prediction_log rows; settles in"
            + " seconds. drift_metrics drill rows are excluded separately via WHERE tag = '' (V027).");
  }

  private static DriftInjectionRequest emptyRequest() {
    return new DriftInjectionRequest(null, null, null, null, null);
  }
}
