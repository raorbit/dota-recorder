package dev.dotarec.fsm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OrphanAdoptionFloorsTest {

    /**
     * The Go remake's TestAdoptionFloorKeysAreTheOnes01xBuilds pins the same rows, so the two apps name
     * a folder the same way and a floor this app wrote still applies after the cut-over.
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "C:\\Users\\X\\Videos             | c:\\users\\x\\videos",
                "C:\\Users\\X\\Videos\\           | c:\\users\\x\\videos",
                "C:/Users/X/Videos//              | c:\\users\\x\\videos",
                "C:\\Users\\X\\Videos\\..\\Videos\\. | c:\\users\\x\\videos",
                "D:\\                             | d:",
                "\\\\NAS\\Share\\                 | \\\\nas\\share",
                "\\\\NAS\\Share\\Dota\\           | \\\\nas\\share\\dota",
                "C:\\Users\\ÉLODIE\\Vidéos        | c:\\users\\élodie\\vidéos",
                "C:\\İ\\ΣΑΣ                       | c:\\i\\σασ",
            })
    void keysAreTheOnesTheGoRemakeBuilds(String root, String key) {
        assertThat(OrphanAdoptionFloors.key(Path.of(root))).isEqualTo(key);
    }

    @Test
    void writesTheVersionedFloorsObjectAndReadsItBack(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(OrphanAdoptionFloors.FILE_NAME);
        Map<String, Long> floors = Map.of("c:\\users\\x\\videos", 1_763_100_000_000L, "d:", 0L);

        OrphanAdoptionFloors.write(file, floors);

        JsonNode written = new ObjectMapper().readTree(file.toFile());
        assertThat(written.get("version").isInt()).isTrue();
        assertThat(written.get("version").intValue()).isEqualTo(1);
        assertThat(written.get("floors").get("c:\\users\\x\\videos").longValue()).isEqualTo(1_763_100_000_000L);
        assertThat(written.get("floors").get("d:").longValue()).isZero();
        assertThat(OrphanAdoptionFloors.read(file)).isEqualTo(floors);
        assertThat(file.resolveSibling(OrphanAdoptionFloors.FILE_NAME + ".tmp")).doesNotExist();
    }

    @Test
    void aMissingFileHoldsNoFloors(@TempDir Path dir) throws Exception {
        assertThat(OrphanAdoptionFloors.read(dir.resolve(OrphanAdoptionFloors.FILE_NAME))).isEmpty();
    }

    @Test
    void aRootWithoutAFloorGetsTheScanStartAndOneWithAFloorKeepsIt(@TempDir Path dir) throws Exception {
        Path known = Files.createDirectories(dir.resolve("known"));
        Path fresh = Files.createDirectories(dir.resolve("fresh"));
        Path file = dir.resolve("data").resolve(OrphanAdoptionFloors.FILE_NAME);
        OrphanAdoptionFloors.write(file, Map.of(OrphanAdoptionFloors.key(known), 1_000L));

        OrphanAdoptionFloors.Snapshot snapshot =
                new OrphanAdoptionFloors(dir.resolve("data")).prepare(known, List.of(fresh), 50_000L, 50_000L);

        assertThat(snapshot.of(known)).isEqualTo(new OrphanAdoptionFloors.Floor(1_000L, true));
        assertThat(snapshot.of(fresh)).isEqualTo(new OrphanAdoptionFloors.Floor(50_000L, true));
        assertThat(snapshot.of(dir.resolve("never-scanned")).admits(Long.MAX_VALUE)).isFalse();
        assertThat(snapshot.of(fresh).admits(49_999L)).isFalse();
        assertThat(snapshot.of(fresh).admits(50_000L)).isTrue();
        assertThat(OrphanAdoptionFloors.read(file))
                .containsEntry(OrphanAdoptionFloors.key(known), 1_000L)
                .containsEntry(OrphanAdoptionFloors.key(fresh), 50_000L);
    }

    @Test
    void theFloorOfAFolderThatIsNoLongerARootIsDropped(@TempDir Path dir) throws Exception {
        Path kept = dir.resolve("kept");
        Path left = dir.resolve("left"); // never created: a missing folder is fine either way
        Path file = dir.resolve("data").resolve(OrphanAdoptionFloors.FILE_NAME);
        OrphanAdoptionFloors.write(
                file, Map.of(OrphanAdoptionFloors.key(kept), 1_000L, OrphanAdoptionFloors.key(left), 2_000L));

        OrphanAdoptionFloors.Snapshot snapshot =
                new OrphanAdoptionFloors(dir.resolve("data")).prepare(kept, List.of(), 50_000L, 50_000L);

        assertThat(snapshot.of(kept)).isEqualTo(new OrphanAdoptionFloors.Floor(1_000L, true));
        assertThat(snapshot.of(left)).isEqualTo(OrphanAdoptionFloors.Floor.NONE);
        assertThat(OrphanAdoptionFloors.read(file)).containsOnlyKeys(OrphanAdoptionFloors.key(kept));

        // Back as a root, it starts again from the scan that sees it.
        assertThat(new OrphanAdoptionFloors(dir.resolve("data")).prepare(kept, List.of(left), 90_000L, 90_000L).of(left))
                .isEqualTo(new OrphanAdoptionFloors.Floor(90_000L, true));
    }

    @Test
    void withoutADataFolderNothingIsAdmitted(@TempDir Path dir) {
        OrphanAdoptionFloors.Snapshot snapshot = new OrphanAdoptionFloors(null).prepare(dir, List.of(), 0L, 0L);

        assertThat(snapshot.of(dir).admits(Long.MAX_VALUE)).isFalse();
    }

    @Test
    void onlyAVideoFolderFlooredThisScanLinksFromTheJournalsEarlierStart(@TempDir Path dir) throws Exception {
        Path video = dir.resolve("video");
        Path archive = dir.resolve("archive");
        OrphanAdoptionFloors floors = new OrphanAdoptionFloors(dir.resolve("data"));

        OrphanAdoptionFloors.Snapshot first = floors.prepare(video, List.of(archive), 50_000L, 20_000L);

        // The link reaches back; the floor every adoption is held to, and the stored one, don't.
        assertThat(first.linkFloorOf(video)).isEqualTo(new OrphanAdoptionFloors.Floor(20_000L, true));
        assertThat(first.of(video)).isEqualTo(new OrphanAdoptionFloors.Floor(50_000L, true));
        assertThat(OrphanAdoptionFloors.read(dir.resolve("data").resolve(OrphanAdoptionFloors.FILE_NAME)))
                .containsEntry(OrphanAdoptionFloors.key(video), 50_000L);
        assertThat(first.linkFloorOf(archive)).isEqualTo(new OrphanAdoptionFloors.Floor(50_000L, true));
        // A later scan finds the floor already there: it is never lowered, and the link no longer
        // reaches back.
        OrphanAdoptionFloors.Snapshot later = floors.prepare(video, List.of(archive), 90_000L, 1_000L);
        assertThat(later.of(video)).isEqualTo(new OrphanAdoptionFloors.Floor(50_000L, true));
        assertThat(later.linkFloorOf(video)).isEqualTo(new OrphanAdoptionFloors.Floor(50_000L, true));
    }
}
