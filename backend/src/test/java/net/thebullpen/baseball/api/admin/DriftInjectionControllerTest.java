package net.thebullpen.baseball.api.admin;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import net.thebullpen.baseball.drift.DriftInjectionService;
import net.thebullpen.baseball.drift.DriftInjectionService.DriftInjectionException;
import net.thebullpen.baseball.drift.DriftInjectionService.InjectionResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Web-layer contract for {@link DriftInjectionController} (E-2, [175]): the drill-sane defaults are
 * applied to a bare {@code POST}, an explicit body overrides them, a caller-fixable {@link
 * DriftInjectionException} maps to 400, and {@code DELETE /synthetic} returns the cleanup count.
 * Standalone MockMvc over a mocked service - the injection mechanics + hygiene are proven in {@code
 * DriftInjectionServiceTest} / {@code DriftInjectionServiceIT}; here we only pin the HTTP mapping.
 */
class DriftInjectionControllerTest {

  private final DriftInjectionService service = mock(DriftInjectionService.class);

  // findAndRegisterModules() pulls in JavaTimeModule so the InjectionResult's Instant fields
  // serialize (the default standalone converter has no JSR-310 support).
  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new DriftInjectionController(service))
          .setMessageConverters(
              new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules()))
          .build();

  private static InjectionResult sampleResult(String model, int rows, String feature) {
    return new InjectionResult(
        model,
        "v2",
        42L,
        rows,
        feature,
        89.0,
        15.0,
        1.0,
        104.0,
        "induced-drill-2026-07",
        Instant.parse("2026-07-15T00:00:00Z"),
        Instant.parse("2026-07-15T20:00:00Z"));
  }

  @Test
  void bare_post_applies_the_drill_defaults() throws Exception {
    when(service.induce(eq("battedball_outcome"), eq(5000), eq(1.0), eq(20), eq("launchSpeedMph")))
        .thenReturn(sampleResult("battedball_outcome", 5000, "launchSpeedMph"));

    mvc.perform(post("/v1/admin/drift/induce"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rowsWritten").value(5000))
        .andExpect(jsonPath("$.shiftFeature").value("launchSpeedMph"))
        .andExpect(jsonPath("$.modelName").value("battedball_outcome"));
  }

  @Test
  void an_explicit_body_overrides_every_default() throws Exception {
    when(service.induce(eq("pitch_outcome_post"), eq(123), eq(2.5), eq(6), eq("launchAngleDeg")))
        .thenReturn(sampleResult("pitch_outcome_post", 123, "launchAngleDeg"));

    String body =
        "{\"modelName\":\"pitch_outcome_post\",\"n\":123,\"shiftSigmas\":2.5,"
            + "\"lookbackHours\":6,\"shiftFeature\":\"launchAngleDeg\"}";
    mvc.perform(
            post("/v1/admin/drift/induce").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rowsWritten").value(123))
        .andExpect(jsonPath("$.shiftFeature").value("launchAngleDeg"));
  }

  @Test
  void a_caller_fixable_injection_failure_maps_to_400() throws Exception {
    when(service.induce(eq("battedball_outcome"), eq(5000), eq(1.0), eq(20), eq("launchSpeedMph")))
        .thenThrow(new DriftInjectionException("refusing to inject: bullpen.drift.tag is empty."));

    mvc.perform(post("/v1/admin/drift/induce")).andExpect(status().isBadRequest());
  }

  @Test
  void delete_synthetic_returns_the_cleanup_count() throws Exception {
    when(service.cleanup()).thenReturn(4200L);

    mvc.perform(delete("/v1/admin/drift/synthetic"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deletedRows").value(4200))
        .andExpect(jsonPath("$.note").exists());
  }
}
