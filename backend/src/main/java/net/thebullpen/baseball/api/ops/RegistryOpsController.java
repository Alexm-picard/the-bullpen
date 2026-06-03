package net.thebullpen.baseball.api.ops;

import java.util.List;
import net.thebullpen.baseball.registry.RegistryService;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Public read-side of the registry — feeds the Ops dashboard (CLAUDE.md no-cut list) without
 * requiring HTTP Basic, since the data is non-sensitive: model names, versions, stages, and the
 * already-public hashes. Decision [29] split + leaf 3a.4 G10 closure.
 *
 * <p>Two endpoints:
 *
 * <ul>
 *   <li>{@code GET /v1/ops/registry/{model_name}} — every version (newest-first), same payload as
 *       the admin list mirror. Returns 200 + empty list for a model with no registrations rather
 *       than 404, because the dashboard polls speculatively and a 404-per-tick fills the logs with
 *       noise.
 *   <li>{@code GET /v1/ops/registry/{model_name}/{version_id}} — one row. 404 if not found.
 * </ul>
 */
@RestController
@RequestMapping("/v1/ops/registry")
@Profile("api")
public class RegistryOpsController {

  private final RegistryService registry;

  public RegistryOpsController(RegistryService registry) {
    this.registry = registry;
  }

  /**
   * Leaf 4e.1: lists every distinct {@code model_name} in the registry, alphabetical. Drives the
   * Ops dashboard's model-name filter dropdown.
   */
  @GetMapping
  public List<String> listAllModelNames() {
    return registry.findAllModelNames();
  }

  /**
   * Every registered version across all models, grouped by name then newest-first. Feeds the Ops
   * dashboard's Model Fleet table in one round-trip. Declared before {@code /{modelName}} so the
   * literal {@code /all} path isn't swallowed by the path variable.
   */
  @GetMapping("/all")
  public List<ModelVersion> listAll() {
    return registry.findAll();
  }

  @GetMapping("/{modelName}")
  public List<ModelVersion> list(@PathVariable String modelName) {
    return registry.findByName(modelName);
  }

  @GetMapping("/{modelName}/{versionId}")
  public ModelVersion get(@PathVariable String modelName, @PathVariable long versionId) {
    ModelVersion mv =
        registry
            .getById(versionId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "no model_version with id " + versionId));
    if (!mv.modelName().equals(modelName)) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "id "
              + versionId
              + " belongs to model "
              + mv.modelName()
              + ", not requested model "
              + modelName);
    }
    return mv;
  }
}
