import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
    jacoco
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "8.8.0"
    id("com.github.spotbugs") version "6.5.9"
    id("net.ltgt.errorprone") version "5.1.0"
    // JMH microbenchmarks for the inference hot path (S1g). Runs via `./gradlew jmh`,
    // nightly in CI against a committed baseline (build is NOT gated on it — JMH
    // timing flaps on shared runners). Creates the `src/jmh/java` source set.
    id("me.champeau.jmh") version "0.7.2"
}

group = "net.thebullpen"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// F3 supply-chain pinning: every resolvable configuration locks to the committed
// gradle.lockfile, so a dependency (direct or transitive) can only change through an
// explicit, reviewable `--write-locks` diff - never silently via a floating version.
// Regenerate after an intentional bump: ./gradlew resolveAndLockAll --write-locks
dependencyLocking {
    lockAllConfigurations()
}

// The Gradle-docs idiom for (re)writing the lockfile across ALL resolvable
// configurations in one pass (the `dependencies` task alone does not touch
// plugin-created configs like spotbugs/errorprone/jmh).
tasks.register("resolveAndLockAll") {
    notCompatibleWithConfigurationCache("Filters configurations at execution time")
    doFirst {
        require(gradle.startParameter.isWriteDependencyLocks) { "Run with --write-locks" }
    }
    doLast {
        configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
    // Guava: needed for Hashing.murmur3_32_fixed() in the A/B router's game-id bucketer (3b.2).
    implementation("com.google.guava:guava:33.4.0-jre")
    implementation("org.flywaydb:flyway-core:11.3.2")
    runtimeOnly("org.xerial:sqlite-jdbc:3.49.1.0")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    // A6 (ADR-0008): error tracking via the Sentry SDK, reporting to a self-hosted
    // GlitchTip (Sentry wire-compatible). BOM keeps the starter + logback appender
    // aligned. Disabled automatically when sentry.dsn is blank (dev/CI/tests).
    implementation(platform("io.sentry:sentry-bom:7.18.0"))
    implementation("io.sentry:sentry-spring-boot-starter-jakarta")
    implementation("io.sentry:sentry-logback")

    // ADR-0007: single S3-compatible client across prod (Cloudflare R2) and offline dev (MinIO).
    // bom keeps the s3 + apache-client + sts versions aligned without listing each explicitly.
    implementation(platform("software.amazon.awssdk:bom:2.30.20"))
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:apache-client")

    implementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
    // EJML for the 15x15 fundamental-matrix inversion in the forward simulator (2a.9).
    // Leaner than Commons Math, actively maintained, simpler API for this use case.
    implementation("org.ejml:ejml-simple:0.43.1")
    implementation("com.clickhouse:clickhouse-jdbc:0.7.2")
    implementation("com.clickhouse:clickhouse-http-client:0.7.2")
    implementation("org.apache.httpcomponents.client5:httpclient5:5.4.3")

    // springdoc: auto-generate the OpenAPI 3 spec from the @RestController surface,
    // served at /v3/api-docs (+ Swagger UI at /swagger-ui.html). The spec is the
    // contract Schemathesis runs against in CI to catch contract↔impl drift.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.4")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
    testImplementation("org.testcontainers:clickhouse:1.21.4")
    testImplementation("org.testcontainers:minio:1.21.4")

    errorprone("com.google.errorprone:error_prone_core:2.50.0")
}

tasks.withType<JavaCompile>().configureEach {
    options.errorprone.disableWarningsInGeneratedCode.set(true)
    options.compilerArgs.addAll(listOf("-Xlint:all"))
}

spotless {
    java {
        googleJavaFormat("1.25.2")
        target("src/**/*.java")
    }
}

spotbugs {
    excludeFilter.set(file("config/spotbugs/exclude.xml"))
}

jmh {
    warmupIterations.set(3)
    iterations.set(5)
    fork.set(1)
    timeUnit.set("us")
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("results/jmh/results.json"))
    includeTests.set(false) // don't generate benchmarks from the test source set
}

// The JMH fat-jar bundles the full Spring dependency tree, which blows past the
// 65535-entry zip limit — enable zip64 so the benchmark archive builds.
tasks.matching { it.name == "jmhJar" }.configureEach {
    (this as Jar).isZip64 = true
}

// Benchmarks are not production code — keep the static-analysis gates off the
// jmh source set (Blackhole patterns + intentional dead returns trip Error Prone
// / SpotBugs). The `jmh` task still compiles + runs them; `build`/`check` don't
// depend on it.
tasks.matching { it.name == "compileJmhJava" }.configureEach {
    (this as JavaCompile).options.errorprone.isEnabled.set(false)
}
tasks.matching { it.name == "spotbugsJmh" }.configureEach { enabled = false }

// Phase 3 accuracy scorecard: bundle the committed promotion-evidence JSONs (and, once the box
// hand-off commits it, the batted-ball backfill artifact) into the JAR as classpath resources under
// accuracy-evidence/, so the public GET /v1/ops/accuracy + /v1/ops/backfill-accuracy read them
// identically in tests and prod with no deploy.sh staging. Sibling-module artifacts copied from
// ../training at build time; the backfill include is a no-op until the box produces that file.
tasks.processResources {
    from("../training/data/eval/promotion") {
        include("*_experiment_results_full*.json")
        into("accuracy-evidence")
    }
    from("../training/data/eval") {
        include("battedball_backfill_accuracy_v1.json")
        into("accuracy-evidence")
    }
    // OFFLINE promotion-gate artifacts (e.g. the carry champion non-inferiority ablation,
    // ADR-0012/[166]) into a SEPARATE classpath dir so AccuracyEvidenceRepository's
    // *_experiment_results_full*.json glob never sees them (they are raw-softmax gate evidence read
    // by the import-offline admin path, NOT public /accuracy scorecard rows).
    from("../training/data/eval/promotion") {
        include("*_promotion_gate.json")
        into("offline-gate-evidence")
    }
}

tasks.named<Test>("test") {
    // @Tag("drill") tests (e.g. the drift-induction drill) are slow, verbose, and
    // run on demand — exclude from normal CI. Run with: ./gradlew test -PrunDrills
    //   --tests "*DriftInductionDrillIT"
    useJUnitPlatform {
        if (!project.hasProperty("runDrills")) {
            excludeTags("drill")
        }
    }
    // Test-only default for the prod-required admin Basic-Auth credential.
    // Individual IT classes still override this via @DynamicPropertySource (the registry-it
    // suite uses 'it-admin:it-password'); this just keeps generic context-load tests
    // (ApplicationTests + the predict-controller suites) from hitting SecurityConfig's
    // blank-value IllegalStateException during context bring-up.
    systemProperty("THEBULLPEN_ADMIN_BASIC_AUTH", "test-admin:test-password")
    // A4: rate limiting off by default in the suite so a chatty @SpringBootTest can't
    // trip the per-IP bucket. RateLimitFilterTest builds its own enabled filter standalone,
    // so it's unaffected by this flag.
    systemProperty("bullpen.ratelimit.enabled", "false")
    // Forward the Testcontainers gate from the Gradle CLI into the forked test JVM
    // (a `-D` on the CLI alone doesn't propagate). Defaults to "false" so a local
    // `./gradlew test` on macOS still SKIPs the @EnabledIfSystemProperty("bullpen.it.docker")
    // ITs (Docker Desktop on macOS breaks Testcontainers); CI passes -Dbullpen.it.docker=true
    // so DriftMetricsRepositoryIT / SnapshotStorageIT / PlayerRepositoryIT /
    // ClickHouseMigrationRunnerIT actually run against ephemeral containers.
    systemProperty("bullpen.it.docker", System.getProperty("bullpen.it.docker", "false"))
    // A2: every `test` run leaves a fresh coverage report behind so `./gradlew test`
    // alone is enough locally; CI uploads the XML/HTML.
    finalizedBy(tasks.named("jacocoTestReport"))
}

// A2 / Wave-4 - coverage measurement plus a binding regression floor. jacocoTestReport always
// publishes the honest baseline (no class exclusions: the denominator is the whole main source
// set, so the percentage isn't quietly massaged). jacocoTestCoverageVerification adds a HARD floor
// a few points under the CI-measured baseline (LINE 82.42% / BRANCH 70.54% on 2026-07-04, full
// suite incl. Docker ITs; up from the 2026-06-15 77.85% / 65.67% as the two-instance + Wave D tests
// landed) so a real coverage regression reds the build without flapping on noise. Re-baselined
// 2026-07-08 (F2.3): un-skipping the @EnabledIf pitch + simulate web tests (~13 methods, incl. the
// previously-0%-covered simulation package) raised the CI baseline further, so the floor moves
// LINE 80 -> 82 and BRANCH 68 -> 70; the exact new baseline is in the backend-test job summary.
//
// The floor is enforced ONLY when the Docker-gated ITs actually ran (-Dbullpen.it.docker=true, i.e.
// CI). A local `./gradlew build` on macOS skips those ITs, which drags coverage below the floor;
// gating that locally would punish every dev run. So the verification task disables itself unless
// the docker gate is set - the floor lives where the full suite runs.
jacoco {
    toolVersion = "0.8.12"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    enabled = System.getProperty("bullpen.it.docker") == "true"
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.82".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.70".toBigDecimal()
            }
        }
    }
}

// `check` (hence `build`) now fails on a coverage regression in CI. Locally, without the docker
// gate, the verification disables itself, so this is a no-op for the normal `./gradlew build`.
tasks.named("check") {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
