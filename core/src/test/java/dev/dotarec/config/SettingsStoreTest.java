package dev.dotarec.config;

import static org.assertj.core.api.Assertions.assertThat;

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
}
