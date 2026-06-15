package net.thebullpen.baseball.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * App-scoped virtual-thread executor for {@code InferenceRouter}'s concurrent champion + shadow
 * dispatch.
 *
 * <p>The router runs the champion and the shadow/challenger as two {@code CompletableFuture}s. The
 * default {@code supplyAsync} (the {@code ForkJoinPool.commonPool()}) is wrong for blocking ONNX
 * inference: the commonPool is bounded (parallelism = #cores - 1) and is NOT replaced by {@code
 * spring.threads.virtual.enabled} (that flag wires the Tomcat request executor + {@code @Async},
 * not the commonPool). A dedicated virtual-thread-per-task executor makes the "both models run
 * concurrently, never stacked" guarantee true regardless of core count and keeps blocking inference
 * off the shared commonPool.
 */
@Configuration
public class InferenceExecutorConfig {

  /**
   * Virtual-thread-per-task executor (a fresh virtual thread per submitted inference). Spring
   * closes it on shutdown - {@link ExecutorService} is {@link AutoCloseable}.
   */
  @Bean(name = "inferenceShadowExecutor")
  public ExecutorService inferenceShadowExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
}
