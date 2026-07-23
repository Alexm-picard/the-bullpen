package net.thebullpen.baseball.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.thebullpen.baseball.api.ApiErrorAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Standalone-MockMvc coverage for the {@link CacheControlAdvice} branching: which prefix gets which
 * header, no-store for predict/admin, and that a non-GET, an out-of-scope path, and an errored read
 * get no cacheable header. Registered via {@code setControllerAdvice} so the real {@code
 * ResponseBodyAdvice} path runs. {@link ApiErrorAdvice} is registered too so an errored read
 * follows the realistic {@code @ExceptionHandler}-writes-a-400-body path (which DOES invoke the
 * advice) - the status guard, not a skipped hook, is what keeps the error uncached.
 *
 * <p>The commit-safety of the advice (the reason it is not a {@code HandlerInterceptor.postHandle})
 * is proven separately, on a real embedded container with a &gt;8 KB body, by {@code
 * CacheControlLargeBodyIT} - MockMvc never commits mid-write, so it cannot cover that.
 */
class CacheControlAdviceTest {

  @RestController
  static class StubController {
    @GetMapping("/v1/ops/routing")
    String ops() {
      return "ok";
    }

    @GetMapping("/v1/ops/registry/all")
    String opsRegistry() {
      return "ok";
    }

    @GetMapping("/v1/games/today")
    String games() {
      return "ok";
    }

    @PostMapping("/v1/predict/pitch")
    String predict() {
      return "ok";
    }

    // The /parks HR heatmap is a POST predict (there is no GET parks/factors endpoint).
    @PostMapping("/v1/predict/batted-ball/all-parks")
    String allParks() {
      return "ok";
    }

    @PostMapping("/v1/admin/routing")
    String admin() {
      return "ok";
    }

    @GetMapping("/v1/players/search")
    String search() {
      return "ok";
    }

    @GetMapping("/v1/ops/latency")
    String latency(@RequestParam("days") int days) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad days");
    }
  }

  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new StubController())
          .setControllerAdvice(new CacheControlAdvice(), new ApiErrorAdvice())
          .build();

  @Test
  void opsReadIsPubliclyCacheableWith20s() throws Exception {
    mvc.perform(get("/v1/ops/routing"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, max-age=20"));
  }

  @Test
  void opsRegistrySubpathIsAlsoCached() throws Exception {
    mvc.perform(get("/v1/ops/registry/all"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, max-age=20"));
  }

  @Test
  void gamesReadIsPubliclyCacheableWith8s() throws Exception {
    mvc.perform(get("/v1/games/today"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, max-age=8"));
  }

  @Test
  void predictIsNoStore() throws Exception {
    mvc.perform(post("/v1/predict/pitch"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
  }

  @Test
  void allParksParksHeatmapIsNoStore() throws Exception {
    // The parks read is a POST predict, so it is covered by the predict no-store rule (never cache
    // a
    // prediction, and it feeds the drift baseline) - NOT a cacheable GET.
    mvc.perform(post("/v1/predict/batted-ball/all-parks"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
  }

  @Test
  void adminIsNoStore() throws Exception {
    mvc.perform(post("/v1/admin/routing"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
  }

  @Test
  void outOfScopeReadGetsNoCacheHeader() throws Exception {
    // player search is not in the cache/no-store scope; the advice leaves it alone.
    mvc.perform(get("/v1/players/search"))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist(HttpHeaders.CACHE_CONTROL));
  }

  @Test
  void erroredReadIsNotCacheable() throws Exception {
    // The 400 is written by ApiErrorAdvice (a body -> the advice IS invoked); the status guard
    // (400 != 200) keeps it out of the cacheable branch, so no cacheable header lands on the error.
    mvc.perform(get("/v1/ops/latency").param("days", "2000000000"))
        .andExpect(status().isBadRequest())
        .andExpect(header().doesNotExist(HttpHeaders.CACHE_CONTROL));
  }

  @Test
  void adviceIsProfileGatedToApiOnly() {
    // The advice is @Profile("api"); the MockMvc + large-body tests register it directly, so
    // neither
    // exercises that gate. Assert the @ControllerAdvice bean loads under `api` and NOT under
    // `worker`
    // (the profile condition is evaluated on register+refresh), so a broken profile string cannot
    // silently disable edge caching in prod.
    try (var api =
        new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
      api.getEnvironment().setActiveProfiles("api");
      api.register(CacheControlAdvice.class);
      api.refresh();
      org.assertj.core.api.Assertions.assertThat(api.getBeanNamesForType(CacheControlAdvice.class))
          .hasSize(1);
    }
    try (var worker =
        new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
      worker.getEnvironment().setActiveProfiles("worker");
      worker.register(CacheControlAdvice.class);
      worker.refresh();
      org.assertj.core.api.Assertions.assertThat(
              worker.getBeanNamesForType(CacheControlAdvice.class))
          .isEmpty();
    }
  }
}
