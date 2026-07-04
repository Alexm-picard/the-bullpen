package net.thebullpen.baseball.api.admin;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import net.thebullpen.baseball.api.admin.dto.PromoteRequest;
import net.thebullpen.baseball.api.dto.OpsEventType;
import net.thebullpen.baseball.data.OpsEventsRepository;
import net.thebullpen.baseball.inference.ModelLoadValidator;
import net.thebullpen.baseball.registry.RegistryException;
import net.thebullpen.baseball.registry.RegistryService;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.RegisterRequest;
import net.thebullpen.baseball.registry.dto.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Write-side admin HTTP surface for the registry — gated by HTTP Basic via {@code SecurityConfig}
 * (the {@code /v1/admin/**} matcher requires role {@code ADMIN}).
 *
 * <p>Three operations:
 *
 * <ul>
 *   <li>{@code GET /v1/admin/registry/{model_name}} — full version list including archived.
 *       Public-read mirror lives on {@link net.thebullpen.baseball.api.ops.RegistryOpsController};
 *       this admin path returns the same shape and is offered behind auth so a future admin UI
 *       doesn't need a separate read API.
 *   <li>{@code POST /v1/admin/registry/{model_name}/register} — proxies to {@link
 *       RegistryService#register(RegisterRequest)} after asserting that the path's {@code
 *       modelName} matches the body's. The body carries the {@code featurePipelinePath} so
 *       feature-schema-hash discipline (3a.3) runs server-side.
 *   <li>{@code POST /v1/admin/registry/{model_name}/promote/{version_id}} — transition stage. The
 *       target stage comes from the body (case-insensitive). Promotion to {@code CHAMPION} triggers
 *       the rule-5 promotion gate inside {@link RegistryService#transitionStage(long, Stage)} —
 *       missing experiment row → HTTP 409.
 * </ul>
 *
 * <p>Exception mapping (matches the sealed {@link RegistryException} hierarchy so the contract is
 * exhaustive at the type level):
 *
 * <ul>
 *   <li>{@code ArtifactMissing} → 422 (caller-fixable; file must exist on the server)
 *   <li>{@code DuplicateVersion} → 409
 *   <li>{@code IllegalTransition} → 409
 *   <li>{@code FeatureSchemaMismatch} → 409
 *   <li>{@code ResetConfirmationMissing} → 400
 *   <li>{@code PromotionCriteriaMissing} → 409 (rule 5 — no passing experiment row)
 *   <li>{@code IllegalArgumentException} from path/body mismatch → 400
 * </ul>
 */
@Tag(
    name = "Admin: Registry",
    description =
        "ADMIN-authed model registry writes: list versions, register a model (feature-schema-hash"
            + " gated), and promote/transition stage (rule-5 promotion gate on CHAMPION). Requires"
            + " HTTP Basic (SecurityConfig /v1/admin/**).")
@RestController
@RequestMapping("/v1/admin/registry")
@Profile("api")
public class RegistryAdminController {

  private static final Logger log = LoggerFactory.getLogger(RegistryAdminController.class);

  private final RegistryService registry;
  private final OpsEventsRepository opsEvents;
  private final ModelLoadValidator loadValidator;
  // INC-2 kill-switch: the load gate is on in prod, but can be disabled in an emergency (e.g. the
  // gate itself misbehaves) and is disabled in the transition-logic ITs that promote dummy models.
  private final boolean loadGateEnabled;

  public RegistryAdminController(
      RegistryService registry,
      OpsEventsRepository opsEvents,
      ModelLoadValidator loadValidator,
      @Value("${bullpen.registry.promotion-load-gate.enabled:true}") boolean loadGateEnabled) {
    this.registry = registry;
    this.opsEvents = opsEvents;
    this.loadValidator = loadValidator;
    this.loadGateEnabled = loadGateEnabled;
  }

  /** Best-effort ops-log emit — an event-log failure must never break a registry operation. */
  private void emit(OpsEventType type, String detail) {
    try {
      opsEvents.record(type, detail);
    } catch (RuntimeException e) {
      log.warn("ops-event emit failed (type={}): {}", type, e.toString());
    }
  }

  @GetMapping("/{modelName}")
  public List<ModelVersion> list(@PathVariable String modelName) {
    return registry.findByName(modelName);
  }

  @PostMapping("/{modelName}/register")
  public ModelVersion register(
      @PathVariable String modelName, @Valid @RequestBody RegisterRequest req) {
    if (!modelName.equals(req.modelName())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "path modelName="
              + modelName
              + " does not match request body modelName="
              + req.modelName());
    }
    try {
      ModelVersion mv = registry.register(req);
      log.info("admin: registered {}/{} (id={})", mv.modelName(), mv.version(), mv.id());
      emit(
          OpsEventType.REGISTER,
          mv.modelName() + " " + mv.version() + " registered as " + mv.stage());
      return mv;
    } catch (RegistryException.ArtifactMissing e) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage(), e);
    } catch (RegistryException.DuplicateVersion | RegistryException.FeatureSchemaMismatch e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
    }
  }

  @PostMapping("/{modelName}/promote/{versionId}")
  public ModelVersion promote(
      @PathVariable String modelName,
      @PathVariable long versionId,
      @Valid @RequestBody PromoteRequest req) {
    Stage target;
    try {
      target = req.parseTargetStage();
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "unknown targetStage='"
              + req.targetStage()
              + "' — expected one of "
              + List.of(Stage.values()),
          e);
    }
    ModelVersion current =
        registry
            .getById(versionId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "no model_version with id " + versionId));
    if (!current.modelName().equals(modelName)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "path modelName="
              + modelName
              + " does not match registered modelName="
              + current.modelName()
              + " for id="
              + versionId);
    }
    // INC-2 (decision [151]) load gate, run BEFORE the write-transaction so the slow ONNX load
    // never
    // holds the SQLite connection. Gate -> CHAMPION (must) and the forward CANDIDATE -> SHADOW (a
    // shadow model is loaded by shadow dispatch). Do NOT gate the CHAMPION -> SHADOW ROLLBACK
    // (INC-1):
    // a broken champion must be demotable to recover - load-gating it would trap it (the 2026-06-07
    // stuck-champion). Resolves the loader from the model's own shape, same as serving.
    boolean loadGate =
        target == Stage.CHAMPION || (target == Stage.SHADOW && current.stage() == Stage.CANDIDATE);
    if (loadGateEnabled && loadGate) {
      try {
        loadValidator.validate(current);
      } catch (RegistryException.ModelLoadFailed e) {
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage(), e);
      }
    }
    try {
      ModelVersion after = registry.transitionStage(versionId, target);
      log.info(
          "admin: promoted {}/{} (id={}) {} -> {} (reason: {})",
          after.modelName(),
          after.version(),
          after.id(),
          current.stage(),
          after.stage(),
          req.reason());
      emit(
          OpsEventType.PROMOTE,
          after.modelName()
              + " "
              + after.version()
              + " "
              + current.stage()
              + " → "
              + after.stage());
      return after;
    } catch (RegistryException.IllegalTransition
        | RegistryException.PromotionCriteriaMissing
        | RegistryException.BaselineMissing e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
    }
  }
}
