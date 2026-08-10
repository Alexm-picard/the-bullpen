package net.thebullpen.baseball.registry;

import java.nio.file.Path;
import net.thebullpen.baseball.Application;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * One-shot CLI entry point for restoring an archived model version from R2 to local disk.
 * Documented in {@code docs/runbooks/registry-snapshot-recovery.md}.
 *
 * <p>Invocation (from the prod host, requires the JAR to use {@code PropertiesLauncher}):
 *
 * <pre>{@code
 * sudo -u bullpen java \
 *   -Dloader.main=net.thebullpen.baseball.registry.RestoreVersionMain \
 *   -jar /opt/bullpen/app.jar <version_id>
 * }</pre>
 *
 * <p>Boots a headless Spring context (no web server, no ingest, no scheduling) to get the registry
 * and R2 beans, runs the restore, prints the local path, and exits. Requires {@code
 * S3_ENDPOINT_URL} to be set (inherited from the systemd EnvironmentFile on the prod host).
 */
public class RestoreVersionMain {

  public static void main(String[] args) {
    if (args.length != 1) {
      System.err.println("Usage: RestoreVersionMain <version_id>");
      System.exit(2);
    }
    long versionId;
    try {
      versionId = Long.parseLong(args[0]);
    } catch (NumberFormatException e) {
      System.err.println("version_id must be a number: " + args[0]);
      System.exit(2);
      return;
    }
    try (ConfigurableApplicationContext ctx =
        new SpringApplicationBuilder(Application.class)
            .web(WebApplicationType.NONE)
            .properties(
                "spring.main.banner-mode=off",
                "bullpen.ingest.live.enabled=false",
                "bullpen.ingest.players.enabled=false",
                "spring.autoconfigure.exclude="
                    + "org.springframework.boot.autoconfigure.web.servlet."
                    + "WebMvcAutoConfiguration")
            .run()) {
      RegistryService service = ctx.getBean(RegistryService.class);
      Path restored = service.restoreVersion(versionId);
      System.out.println("restored version " + versionId + " to " + restored);
    } catch (Exception e) {
      System.err.println("restore failed: " + e.getMessage());
      e.printStackTrace(System.err);
      System.exit(1);
    }
  }
}
