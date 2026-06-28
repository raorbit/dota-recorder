package dev.dotarec.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dotarec.config.SettingsStore.AudioSource;
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
    void freshStore_seedsExactlyOneDotaApplicationAudioSource(@TempDir Path dir) {
        SettingsStore store = new SettingsStore(paths(dir));

        assertThat(store.get().audioSources).hasSize(1);
        AudioSource seed = store.get().audioSources.get(0);
        assertThat(seed.kind()).isEqualTo("application");
        assertThat(seed.target()).isEqualTo("::dota2.exe");
        assertThat(seed.volume()).isEqualTo(100);
        assertThat(seed.muted()).isFalse();
        assertThat(seed.id()).isNotBlank();
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

        assertThat(store.get().audioSources).hasSize(1);
        assertThat(store.get().audioSources.get(0).id()).isEqualTo("a");
        assertThat(store.get().audioSources.get(0).muted()).isTrue();

        // And it round-trips through settings.json.
        SettingsStore reloaded = new SettingsStore(paths(dir));
        assertThat(reloaded.get().audioSources).hasSize(1);
        assertThat(reloaded.get().audioSources.get(0).target()).isEqualTo("mic");
    }
}
