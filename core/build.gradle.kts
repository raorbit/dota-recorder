plugins {
    java
    id("org.springframework.boot") version "3.3.13"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "dev.dotarec"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Web + WebSocket bridge (REST/WebSocket on 127.0.0.1:3224) and the
    // second Tomcat connector for GSI ingest on 127.0.0.1:3223. Jackson is
    // pulled in transitively by starter-web.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")

    // SQLite access via plain JDBC. JOOQ codegen is a documented TODO below
    // and is intentionally NOT wired for the v0.1 foundation.
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")

    // OBS control over obs-websocket protocol v5 (OBS 28+). This is the
    // community fork that targets v5; the old net.twasi client is v4-only and
    // would NOT authenticate against a stock modern OBS. Pulls in Jetty's
    // websocket-client + Gson transitively, isolated from Spring's Jackson.
    // Coordinate confirmed to resolve from Maven Central.
    implementation("io.obs-websocket.community:client:2.0.0") {
        // This client bundles slf4j-simple, a SECOND SLF4J binding that competes
        // with Spring Boot's Logback. SLF4J picks a provider by classpath order, so
        // it intermittently selects slf4j-simple -> Spring's LogbackLoggingSystem
        // throws at startup ("not a Logback LoggerContext") AND file logging breaks.
        // Exclude it so Logback is the only binding.
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }

    // Test stack: JUnit 5 + AssertJ (the `test` task already declares
    // useJUnitPlatform()). Brings the JUnit Jupiter engine the runner needs.
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    // The Electron supervisor launches <resourcesPath>/core/core.jar, so the
    // packaged artifact name is part of the runtime contract.
    archiveFileName.set("core.jar")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

// TODO(plan Step 3 - JOOQ codegen): once the schema is stable, generate typed
//   records from the migrated SQLite database. JOOQ generates FROM a schema, it
//   is not a migration tool, so the hand-rolled user_version migration runner
//   must run first, then codegen against the migrated DB. Sketch:
//
//   plugins { id("nu.studer.jooq") version "<version>" }
//   jooq { configurations { create("main") { ... jdbc { driver = "org.sqlite.JDBC"
//     url = "jdbc:sqlite:build/codegen/schema.sqlite" } ... } } }
//   tasks.named("generateJooq") { dependsOn("applyMigrationsForCodegen") }
//
//   NOTE: the JOOQ SQLite path has known sharp edges (TEXT enum-like columns
//   map to String and need forcedTypes; affinity is ignored). Defer until the
//   schema is locked.
