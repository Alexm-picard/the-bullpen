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
    implementation("org.flywaydb:flyway-core:11.3.2")
    runtimeOnly("org.xerial:sqlite-jdbc:3.49.1.0")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    implementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
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
}
