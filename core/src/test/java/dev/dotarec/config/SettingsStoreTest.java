package dev.dotarec.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dotarec.config.SettingsStore.AudioSource;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the settings persistence + the {@code Settings.copy()} discipline: every field must survive
 * an {@link SettingsStore#update} (which copies-then-mutates) and a reload from disk. A new field
 * missing from {@code copy()} would be silently wiped on the next update — exactly the trap
 * {@code gsiAuthToken} could fall into.
 */
class SettingsStoreTest {

    private static AppPaths paths(Path dir) {
        return new AppPaths(dir.toString(), dir.resolve("obs").toString());
    }

    @Test
    void freshStore_hasBlankGsiAuthToken(@TempDir Path dir) {
        SettingsStore store = new SettingsStore(paths(dir));
        assertThat(store.get().gsiAuthToken).isEmpty();
    }

    @Test
    void gsiAuthToken_survivesUpdateAndReload(@TempDir Path dir) {
        SettingsStore store = new SettingsStore(paths(dir));
        store.update(
                s -> {
                    s.gsiAuthToken = "tok-abc123";
                    return s;
                });

        // Still set in memory after the copy-on-write update (copy() carries it).
        assertThat(store.get().gsiAuthToken).isEqualTo("tok-abc123");

        // And it round-trips through settings.json: a brand-new store over the same dir reloads it.
        SettingsStore reloaded = new SettingsStore(paths(dir));
        assertThat(reloaded.get().gsiAuthToken).isEqualTo("tok-abc123");
    }

    @Test
    void unrelatedUpdate_doesNotWipeAPreviouslySetToken(@TempDir Path dir) {
        SettingsStore store = new SettingsStore(paths(dir));
        store.update(
                s -> {
                    s.gsiAuthToken = "keep-me";
                    return s;
                });

        // A later update that only touches another field must not drop the token (the copy() trap).
        store.update(
                s -> {
                    s.resolution = "2560x1440";
                    return s;
                });

        assertThat(store.get().gsiAuthToken).isEqualTo("keep-me");
        assertThat(store.get().resolution).isEqualTo("2560x1440");
    }

    @Test
    void freshStore_hasVideoControlDefaults(@TempDir Path dir) {
        SettingsStore store = new SettingsStore(paths(dir));
        assertThat(store.get().fps).isEqualTo(60);
        assertThat(store.get().quality).isEqualTo("HQ");
        assertThat(store.get().format).isEqualTo("hybrid_mp4");
    }

    @Test
    void videoControls_surviveUpdateAndReload(@TempDir Path dir) {
        SettingsStore store = new SettingsStore(paths(dir));
        store.update(
                s -> {
                    s.fps = 30;
                    s.quality = "Stream";
                    s.format = "mkv";
                    return s;
                });

        // copy() carries all three across an unrelated update (the copy() trap).
        store.update(
                s -> {
                    s.resolution = "1280x720";
                    return s;
                });
        assertThat(store.get().fps).isEqualTo(30);
        assertThat(store.get().quality).isEqualTo("Stream");
        assertThat(store.get().format).isEqualTo("mkv");

        // And they round-trip through settings.json.
        SettingsStore reloaded = new SettingsStore(paths(dir));
        assertThat(reloaded.get().fps).isEqualTo(30);
        assertThat(reloaded.get().quality).isEqualTo("Stream");
        assertThat(reloaded.get().format).isEqualTo("mkv");
    }

    @Test
    void load_backfillsVideoControlsFromLegacyJson(@TempDir Path dir) throws Exception {
        // A settings.json predating fps/quality/format deserializes fps to 0 and quality/format to
        // null; load() must backfill the defaults so writeProfile never substitutes "0"/null.
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(
                dir.resolve("settings.json"), "{\"resolution\":\"1920x1080\",\"fps\":0}");

        SettingsStore store = new SettingsStore(paths(dir));
        assertThat(store.get().fps).isEqualTo(60);
        assertThat(store.get().quality).isEqualTo("HQ");
        assertThat(store.get().format).isEqualTo("hybrid_mp4");
    }

    @Test
    void freshStore_seedsDotaOnPlusTwoBuiltinRowsOff(@TempDir Path dir) {
        SettingsStore store = new SettingsStore(paths(dir));

        // Fresh install = the Dota game capture (on) + the two always-present built-in rows (off).
        assertThat(store.get().audioSources).hasSize(3);

        AudioSource game = store.get().audioSources.get(0);
        assertThat(game.kind()).isEqualTo("application");
        assertThat(game.target()).isEqualTo("::dota2.exe");
        assertThat(game.volume()).isEqualTo(100);
        assertThat(game.muted()).isFalse();
        assertThat(game.id()).isNotBlank();

        AudioSource mic = byId(store, SettingsStore.BUILTIN_MICROPHONE_ID);
        assertThat(mic.kind()).isEqualTo("input");
        assertThat(mic.muted()).isTrue(); // off by default — no surprise mic capture

        AudioSource desktop = byId(store, SettingsStore.BUILTIN_DESKTOP_ID);
        assertThat(desktop.kind()).isEqualTo("output");
        assertThat(desktop.muted()).isTrue(); // off by default — no surprise desktop/Discord capture
    }

    @Test
    void load_backfillsBuiltinRowsForAnExistingList_preservingTheUsersApps(@TempDir Path dir)
            throws Exception {
        // An existing install (predating the mixer) has only its Dota app source. load() must migrate
        // it onto the mixer by appending the two off built-in rows WITHOUT touching the app source.
        Files.createDirectories(dir);
        Files.writeString(
                dir.resolve("settings.json"),
                "{\"audioSources\":[{\"id\":\"dota-1\",\"kind\":\"application\","
                        + "\"target\":\"::dota2.exe\",\"label\":\"Dota 2\",\"volume\":100,\"muted\":false}]}");

        SettingsStore store = new SettingsStore(paths(dir));

        assertThat(store.get().audioSources).hasSize(3);
        assertThat(byId(store, "dota-1").target()).isEqualTo("::dota2.exe");
        assertThat(byId(store, SettingsStore.BUILTIN_MICROPHONE_ID).muted()).isTrue();
        assertThat(byId(store, SettingsStore.BUILTIN_DESKTOP_ID).muted()).isTrue();
    }

    @Test
    void load_doesNotDuplicateOrResetAnAlreadyPresentBuiltinRow(@TempDir Path dir) throws Exception {
        // A user who turned their microphone ON (muted=false, custom volume) must keep that state: the
        // backfill is keyed by reserved id, so it neither duplicates the row nor resets its toggle.
        Files.createDirectories(dir);
        Files.writeString(
                dir.resolve("settings.json"),
                "{\"audioSources\":[{\"id\":\"" + SettingsStore.BUILTIN_MICROPHONE_ID + "\","
                        + "\"kind\":\"input\",\"target\":\"default\",\"label\":\"Microphone\","
                        + "\"volume\":60,\"muted\":false}]}");

        SettingsStore store = new SettingsStore(paths(dir));

        long mics =
                store.get().audioSources.stream()
                        .filter(s -> SettingsStore.BUILTIN_MICROPHONE_ID.equals(s.id()))
                        .count();
        assertThat(mics).isEqualTo(1); // not duplicated
        AudioSource mic = byId(store, SettingsStore.BUILTIN_MICROPHONE_ID);
        assertThat(mic.muted()).isFalse(); // the user's "on" survived
        assertThat(mic.volume()).isEqualTo(60); // and their volume
        // Desktop was absent, so it is still backfilled (off).
        assertThat(byId(store, SettingsStore.BUILTIN_DESKTOP_ID).muted()).isTrue();
    }

    /** The one audio source with the given id (fails the test if absent). */
    private static AudioSource byId(SettingsStore store, String id) {
        return store.get().audioSources.stream()
                .filter(s -> id.equals(s.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no audio source with id " + id));
    }

    @Test
    void save_isAtomic_leavesNoLeftoverTempAndFileParses(@TempDir Path dir) throws Exception {
        SettingsStore store = new SettingsStore(paths(dir));
        store.update(s -> { s.resolution = "1280x720"; return s; });
        // A second save exercises the replace path (the file already exists).
        store.update(s -> { s.resolution = "2560x1440"; return s; });

        Path file = dir.resolve("settings.json");
        // No discardable temp left behind, and the file still parses cleanly into a fresh store.
        assertThat(Files.exists(dir.resolve("settings.json.tmp"))).isFalse();
        assertThat(new SettingsStore(paths(dir)).get().resolution).isEqualTo("2560x1440");
        assertThat(Files.isReadable(file)).isTrue();
    }

    @Test
    void save_writesOneDeepBakOfPreviousVersion(@TempDir Path dir) throws Exception {
        SettingsStore store = new SettingsStore(paths(dir));
        store.update(s -> { s.resolution = "AAA-version"; return s; }); // version A
        store.update(s -> { s.resolution = "BBB-version"; return s; }); // version B

        // The .bak holds the version that was on disk BEFORE the latest save (A), not the current (B).
        Path bak = dir.resolve("settings.json.bak");
        assertThat(Files.isReadable(bak)).isTrue();
        assertThat(Files.readString(bak)).contains("AAA-version").doesNotContain("BBB-version");
        // The live file is the latest version.
        assertThat(new SettingsStore(paths(dir)).get().resolution).isEqualTo("BBB-version");
    }

    @Test
    void load_recoversFromBakWhenPrimaryCorrupt(@TempDir Path dir) throws Exception {
        // Establish a good .bak by saving twice (the first save's content rolls into .bak on the
        // second), with a recoverable secret in the backed-up version.
        SettingsStore store = new SettingsStore(paths(dir));
        store.update(
                s -> {
                    s.gsiAuthToken = "secret-token";
                    s.accountId = 96828122L;
                    return s;
                });
        store.update(s -> { s.resolution = "1280x720"; return s; });

        // The most recent save wrote the secret into .bak (the prior on-disk version held it). Now
        // truncate the primary settings.json so it fails to parse.
        Files.writeString(dir.resolve("settings.json"), "{ this is not valid json");

        // A new store over the same dir must recover the secret from .bak, not fall back to defaults.
        SettingsStore reloaded = new SettingsStore(paths(dir));
        assertThat(reloaded.get().gsiAuthToken).isEqualTo("secret-token");
        assertThat(reloaded.get().accountId).isEqualTo(96828122L);
    }

    @Test
    void audioSources_survivesUpdateAndUnrelatedUpdate(@TempDir Path dir) {
        SettingsStore store = new SettingsStore(paths(dir));
        store.update(
                s -> {
                    s.audioSources =
                            new java.util.ArrayList<>(
                                    java.util.List.of(
                                            new AudioSource("a", "input", "mic", "Mic", 70, true)));
                    return s;
                });

        // The copy() must carry the list across an unrelated update (the copy() trap).
        store.update(
                s -> {
                    s.resolution = "1280x720";
                    return s;
                });

        // In memory the list is exactly what the user set (load()'s backfill doesn't run on update()).
        assertThat(store.get().audioSources).hasSize(1);
        assertThat(store.get().audioSources.get(0).id()).isEqualTo("a");
        assertThat(store.get().audioSources.get(0).muted()).isTrue();

        // And the user's source round-trips through settings.json; the reload also backfills the two
        // built-in mic/desktop rows alongside it (they must always exist).
        SettingsStore reloaded = new SettingsStore(paths(dir));
        assertThat(reloaded.get().audioSources).hasSize(3);
        assertThat(byId(reloaded, "a").target()).isEqualTo("mic");
        assertThat(byId(reloaded, SettingsStore.BUILTIN_MICROPHONE_ID)).isNotNull();
        assertThat(byId(reloaded, SettingsStore.BUILTIN_DESKTOP_ID)).isNotNull();
    }

    @Test
    void clearedAppSources_stayCleared_butBuiltinRowsAreGuaranteed(@TempDir Path dir) {
        SettingsStore store = new SettingsStore(paths(dir));
        // Fresh install: Dota app capture + the two built-in rows.
        assertThat(store.get().audioSources).hasSize(3);

        // The user clears every audio source (an explicit empty list, not a missing field).
        store.update(
                s -> {
                    s.audioSources = new java.util.ArrayList<>();
                    return s;
                });

        // A reload must NOT resurrect the Dota app capture (an explicit-empty list is durable for app
        // sources) — but the two built-in mic/desktop rows ARE re-added (off), since they must always
        // exist so the user can see and control them.
        SettingsStore reloaded = new SettingsStore(paths(dir));
        assertThat(reloaded.get().audioSources).hasSize(2);
        assertThat(reloaded.get().audioSources)
                .extracting(AudioSource::id)
                .containsExactlyInAnyOrder(
                        SettingsStore.BUILTIN_MICROPHONE_ID, SettingsStore.BUILTIN_DESKTOP_ID);
        assertThat(reloaded.get().audioSources).allMatch(AudioSource::muted);
        // The removed Dota application capture is NOT brought back.
        assertThat(reloaded.get().audioSources).noneMatch(s -> "application".equals(s.kind()));
    }
}
