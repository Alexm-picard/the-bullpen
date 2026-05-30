import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
    id("org.springframework.boot") version "3.5.4"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "7.0.2"
    id("com.github.spotbugs") version "6.0.27"
    id("net.ltgt.errorprone") version "4.1.0"
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
    implementation("org.apache.httpcomponents.client5:httpclient5:5.4.1")

    // springdoc: auto-generate the OpenAPI 3 spec from the @RestController surface,
    // served at /v3/api-docs (+ Swagger UI at /swagger-ui.html). The spec is the
    // contract Schemathesis runs against in CI to catch contract↔impl drift.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.4")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter:1.20.6")
    testImplementation("org.testcontainers:clickhouse:1.20.6")
    testImplementation("org.testcontainers:minio:1.20.6")

    errorprone("com.google.errorprone:error_prone_core:2.36.0")
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
}
