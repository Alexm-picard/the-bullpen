package net.thebullpen.baseball.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The regression test the {@link CacheControlAdvice}'s whole reason-for-being rests on: on a REAL
 * embedded Tomcat, the Cache-Control header must survive a response larger than the container's ~8
 * KB output buffer. A {@code HandlerInterceptor.postHandle} would fail here (the response commits
 * mid body-write, so the late {@code setHeader} is dropped) - and MockMvc could never catch it,
 * because {@code MockHttpServletResponse} never commits mid-write. {@code ResponseBodyAdvice} sets
 * the header before the converter writes, so it holds regardless of size.
 *
 * <p>Minimal self-contained context (a stub controller + the advice bean, web auto-config only, no
 * datasource / flyway / security), so it needs no ClickHouse and boots fast.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = CacheControlLargeBodyTest.TestApp.class)
class CacheControlLargeBodyTest {

  private static final int LARGE_BODY_CHARS = 100_000; // well past Tomcat's ~8 KB output buffer

  @Autowired private TestRestTemplate rest;

  @Test
  void cacheHeaderSurvivesAResponseLargerThanTheContainerOutputBuffer() {
    ResponseEntity<String> resp = rest.getForEntity("/v1/games/big", String.class);
    assertThat(resp.getStatusCode().value()).isEqualTo(200);
    assertThat(resp.getBody()).hasSize(LARGE_BODY_CHARS); // the response really did commit its body
    assertThat(resp.getHeaders().getCacheControl()).isEqualTo("public, max-age=8");
  }

  @Test
  void smallReadAlsoGetsTheHeaderThroughRealDispatch() {
    ResponseEntity<String> resp = rest.getForEntity("/v1/ops/routing", String.class);
    assertThat(resp.getHeaders().getCacheControl()).isEqualTo("public, max-age=20");
  }

  // Import ONLY the web-serving auto-configs (not the blanket @EnableAutoConfiguration, which drags
  // in datasource/actuator/etc. beans that fail without config in this stripped context).
  @ImportAutoConfiguration({
    ServletWebServerFactoryAutoConfiguration.class,
    DispatcherServletAutoConfiguration.class,
    WebMvcAutoConfiguration.class,
    HttpMessageConvertersAutoConfiguration.class,
    JacksonAutoConfiguration.class
  })
  @Configuration
  static class TestApp {

    // Explicit bean (bypasses the @Profile("api") on the class, which is not evaluated for an
    // explicitly-registered @Bean); MVC still detects it as a @ControllerAdvice /
    // ResponseBodyAdvice.
    @Bean
    CacheControlAdvice cacheControlAdvice() {
      return new CacheControlAdvice();
    }

    @RestController
    static class BigController {
      @GetMapping("/v1/games/big")
      String big() {
        return "x".repeat(LARGE_BODY_CHARS);
      }

      @GetMapping("/v1/ops/routing")
      String ops() {
        return "ok";
      }
    }
  }
}
