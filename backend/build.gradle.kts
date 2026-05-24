import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
    id("org.springframework.boot") version "3.5.4"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "7.0.2"
    id("com.github.spotbugs") version "6.0.27"
    id("net.ltgt.errorprone") version "4.1.0"
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
    implementation("org.flywaydb:flyway-core:11.3.2")
    runtimeOnly("org.xerial:sqlite-jdbc:3.49.1.0")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    implementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
    // EJML for the 15x15 fundamental-matrix inversion in the forward simulator (2a.9).
    // Leaner than Commons Math, actively maintained, simpler API for this use case.
    implementation("org.ejml:ejml-simple:0.43.1")
    implementation("com.clickhouse:clickhouse-jdbc:0.7.2")
    implementation("com.clickhouse:clickhouse-http-client:0.7.2")
    implementation("org.apache.httpcomponents.client5:httpclient5:5.4.1")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("org.testcontainers:clickhouse:1.20.4")

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

tasks.named<Test>("test") {
    useJUnitPlatform()
    // Test-only default for the prod-required admin Basic-Auth credential.
    // Individual IT classes still override this via @DynamicPropertySource (the registry-it
    // suite uses 'it-admin:it-password'); this just keeps generic context-load tests
    // (ApplicationTests + the predict-controller suites) from hitting SecurityConfig's
    // blank-value IllegalStateException during context bring-up.
    systemProperty("THEBULLPEN_ADMIN_BASIC_AUTH", "test-admin:test-password")
}
