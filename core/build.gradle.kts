plugins {
    java
    id("org.springframework.boot") version "3.3.13"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "dev.dotarec"
version = "0.1.5"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    dependencies {
        // The obs-websocket community client (below) targets Jetty 9.4's websocket-client, whose
        // WebSocketClient.<clinit> references org.eclipse.jetty.util.log.Log -- a class removed in
        // Jetty 10+. Spring Boot's BOM otherwise bumps the transitive jetty support artifacts
        // (util/io/http/client/...) to 12.x, so the 9.4 websocket-client fails to initialize at
        // runtime (NoClassDefFoundError) and OBS never connects -> no recording. Nothing else uses
        // Jetty (Spring runs on embedded Tomcat), so pin the whole support graph to the matching
        // 9.4.49 line that the websocket-client expects.
        dependencySet("org.eclipse.jetty:9.4.49.v20220914") {
            entry("jetty-util")
            entry("jetty-io")
            entry("jetty-http")
            entry("jetty-client")
            entry("jetty-alpn-client")
            entry("jetty-xml")
        }
    }
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

    // Connection pool for the SQLite DataSource. The GSI feed drives ~10Hz journal writes during a
    // recording; an unpooled SQLiteDataSource opens/closes a physical file handle (re-running the
    // PRAGMAs) on every call. Hikari keeps a small warm pool so those writes reuse a connection.
    // Version is managed by the Spring Boot BOM (Hikari is Boot's default pool).
    implementation("com.zaxxer:HikariCP")

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
    manifest {
        // The obs-websocket community client deserializes OBS events with Gson, which
        // reflectively instantiates java.lang.Void for empty event payloads (e.g. the
        // RecordStateChanged/OUTPUT_STARTED event). On JDK 16+ strong encapsulation blocks
        // setAccessible on java.lang unless it is opened -- without this the OUTPUT_STARTED
        // confirmation never parses, recording is never confirmed, and every match aborts.
        // Set in the manifest so it applies however the jar is launched (java -jar standalone
        // and the supervisor's javaw -jar alike), not just under one launcher.
        attributes("Add-Opens" to "java.base/java.lang")
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

// Trimmed JRE image bundled with the installer so users need no Java installed.
// The Electron supervisor launches <resourcesPath>/jre/bin/javaw.exe -jar core.jar.
//
// Uses the java.se aggregator module (+ a few jdk.* modules some libs reach for)
// rather than a hand-picked minimal set: a too-aggressive jlink trim fails at
// RUNTIME (ClassNotFound) not build time, so v0.1 prioritizes "runs anything"
// (~60MB, jmod-free) over maximal trimming. Narrow it later via jdeps if needed.
tasks.register<Exec>("jlinkImage") {
    group = "distribution"
    description = "Build a trimmed JRE image at core/build/jre-image for bundling."
    dependsOn("bootJar")
    val imageDir = layout.buildDirectory.dir("jre-image")
    val launcher = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    outputs.dir(imageDir)
    doFirst {
        // jlink refuses to write into an existing directory.
        delete(imageDir)
        val jlink = launcher.get().metadata.installationPath
            .file("bin/jlink.exe").asFile.absolutePath
        commandLine(
            jlink,
            "--add-modules",
            "java.se,jdk.unsupported,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.zipfs,jdk.management",
            "--strip-debug", "--no-header-files", "--no-man-pages", "--compress", "2",
            "--output", imageDir.get().asFile.absolutePath,
        )
    }
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
