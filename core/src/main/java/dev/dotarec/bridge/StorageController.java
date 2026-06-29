package dev.dotarec.bridge;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.dotarec.config.SettingsStore;
import dev.dotarec.config.SettingsStore.StorageLocation;
import dev.dotarec.data.ClipRepository;
import dev.dotarec.data.ClipRow;
import dev.dotarec.data.MatchRepository;
import dev.dotarec.data.MatchSummary;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reports real disk usage per configured storage location so the settings UI can show free/total
 * space and warn when a drive's cap exceeds its physical capacity.
 *
 * <p>{@code GET /storage/usage} → an ordered list: the active recording drive first (role
 * {@code active}, cap = {@code retentionCapGb}), then each archive drive (role {@code archive}).
 * {@code usedBytes} is the stored VOD + clip bytes under that path (from the DB), matching what the
 * retention sweeper and archiver count against the caps; {@code freeBytes}/{@code totalBytes} come
 * from the filesystem and are null when the drive can't be stat'd (e.g. a configured path that
 * doesn't exist yet).
 */
@RestController
public class StorageController {

    private static final long BYTES_PER_GB = 1024L * 1024 * 1024;

    private final SettingsStore settings;
    private final MatchRepository matches;
    private final ClipRepository clips;

    public StorageController(SettingsStore settings, MatchRepository matches, ClipRepository clips) {
        this.settings = settings;
        this.matches = matches;
        this.clips = clips;
    }

    @GetMapping("/storage/usage")
    public StorageUsage usage() {
        SettingsStore.Settings s = settings.get();
        List<Drive> drives = new ArrayList<>();
        if (s.videoDir != null && !s.videoDir.isBlank()) {
            drives.add(new Drive("active", s.videoDir, (long) Math.max(s.retentionCapGb, 0) * BYTES_PER_GB));
        }
        if (s.storageLocations != null) {
            for (StorageLocation loc : s.storageLocations) {
                if (loc != null && loc.path() != null && !loc.path().isBlank()) {
                    drives.add(new Drive("archive", loc.path(), (long) Math.max(loc.capGb(), 0) * BYTES_PER_GB));
                }
            }
        }

        // One pass over stored VODs AND clips, attributing each file's bytes to the drive it lives
        // under. Clips are first-class stored files the sweeper/archiver also count, so omitting them
        // here understates real usage (and the starred total below).
        List<MatchSummary> all = matches.findAll();
        List<ClipRow> allClips = clips.findAll();
        List<DriveUsage> out = new ArrayList<>(drives.size());
        for (Drive d : drives) {
            long used = 0L;
            String dirPrefix = prefix(d.path());
            for (MatchSummary m : all) {
                if (m.videoPath() == null || m.videoPath().isBlank() || m.fileSizeBytes() == null) {
                    continue;
                }
                if (normalize(m.videoPath()).startsWith(dirPrefix)) {
                    used += m.fileSizeBytes();
                }
            }
            for (ClipRow c : allClips) {
                if (c.videoPath() == null || c.videoPath().isBlank() || c.fileSizeBytes() == null) {
                    continue;
                }
                if (normalize(c.videoPath()).startsWith(dirPrefix)) {
                    used += c.fileSizeBytes();
                }
            }
            Long free = null;
            Long total = null;
            try {
                var store = Files.getFileStore(Path.of(d.path()));
                free = store.getUsableSpace();
                total = store.getTotalSpace();
            } catch (IOException | RuntimeException e) {
                // Drive not present/readable yet: report nulls rather than failing the whole call.
            }
            out.add(new DriveUsage(d.role(), d.path(), d.capBytes(), used, free, total));
        }

        // Totals across all stored VODs and clips (regardless of which drive): everything, and the
        // starred subset that the retention sweeper never auto-deletes. A clip carries its own star,
        // independent of its parent match, so a starred clip's bytes are permanent and count here too.
        long totalBytes = 0L;
        long starredBytes = 0L;
        for (MatchSummary m : all) {
            if (m.videoPath() == null || m.videoPath().isBlank() || m.fileSizeBytes() == null) {
                continue;
            }
            totalBytes += m.fileSizeBytes();
            if (m.starred()) {
                starredBytes += m.fileSizeBytes();
            }
        }
        for (ClipRow c : allClips) {
            if (c.videoPath() == null || c.videoPath().isBlank() || c.fileSizeBytes() == null) {
                continue;
            }
            totalBytes += c.fileSizeBytes();
            if (c.starred()) {
                starredBytes += c.fileSizeBytes();
            }
        }
        return new StorageUsage(out, totalBytes, starredBytes);
    }

    private static String prefix(String dir) {
        String n = normalize(dir);
        return n.endsWith(File.separator) ? n : n + File.separator;
    }

    private static String normalize(String path) {
        try {
            return Path.of(path).toAbsolutePath().normalize().toString().toLowerCase();
        } catch (RuntimeException e) {
            return path.toLowerCase();
        }
    }

    private record Drive(String role, String path, long capBytes) {}

    /**
     * Storage usage: the per-drive rows plus library-wide totals. {@code totalBytes} is every stored
     * VOD and clip; {@code starredBytes} is the starred subset (never auto-deleted by retention).
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record StorageUsage(List<DriveUsage> drives, long totalBytes, long starredBytes) {}

    /** Per-drive usage row. Null free/total when the drive can't be stat'd. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DriveUsage(
            String role, String path, long capBytes, long usedBytes, Long freeBytes, Long totalBytes) {}
}
