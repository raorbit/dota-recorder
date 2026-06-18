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

    // TODO(plan Step 4 - OBS control): add obs-websocket-java once the
    //   recording pipeline lands, e.g.
    //   implementation("io.obs-websocket.community:client:<version>")
    //   Reference only - do NOT add until the OBS step is implemented.
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
