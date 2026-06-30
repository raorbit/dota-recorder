import { useEffect, useState } from 'react';
import {
  fetchAudioInputs,
  fetchScenePreview,
  fetchSettings,
  fetchStorageUsage,
  updateSettings,
  BUILTIN_DESKTOP_ID,
  BUILTIN_MICROPHONE_ID,
  type AudioInputOption,
  type AudioSource,
  type AudioSourceKind,
  type DriveUsage,
  type ScenePreview,
  type Settings,
  type SettingsPatch,
  type StorageLocation,
  type StorageUsage,
} from '../../api/client';
import type { StatusSnapshot } from '../../api/client';
import './recording-settings.css';

type LoadState = 'loading' | 'ready' | 'error';
type SaveState = 'idle' | 'saving' | 'saved' | 'error';

interface RecordingSettingsProps {
  // Live recorder status, lifted from App's StatusSocket. Null until the first
  // frame (or while the core is unreachable) so we can render an "unknown" state
  // rather than a misleading error.
  readonly obs: StatusSnapshot['obs'] | null;
}

// Resolution presets offered in the dropdown. A stored value outside this list
// (e.g. an ultrawide) is preserved and shown as an extra leading option.
const RES_PRESETS: ReadonlyArray<{ readonly value: string; readonly label: string }> = [
  { value: '1280x720', label: '1280 × 720 (720p)' },
  { value: '1920x1080', label: '1920 × 1080 (1080p)' },
  { value: '2560x1440', label: '2560 × 1440 (1440p)' },
  { value: '3840x2160', label: '3840 × 2160 (4K)' },
];

// The core auto-probes a hardware encoder and writes back a short OBS token; map
// it to a human label. Unknown tokens fall through to the raw value.
const ENCODER_LABELS: Record<string, string> = {
  nvenc: 'NVIDIA NVENC (H.264)',
  amd: 'AMD AMF (H.264)',
  qsv: 'Intel QuickSync (H.264)',
  x264: 'x264 (software)',
};

// The encoder-override picker offers `auto` (the blank sentinel — re-arms the GPU
// probe at boot) plus the four EncoderProbe tokens. Any other string silently falls
// back to x264 in OBS, so only these are offered.
const ENCODER_OVERRIDE_TOKENS: ReadonlyArray<string> = ['x264', 'nvenc', 'qsv', 'amd'];

// The fps/quality/format value sets below mirror the server-side allow-lists in
// SettingsController.ALLOWED_* — keep them in sync (the core 400s a value outside its set).
//
// Frame-rate presets. OBS "Common FPS" integers only (FPSType stays 0); 120/144 would
// need FPSType=1 and fractional rates (29.97) need a String, so the int field is
// restricted to 30/60.
const FPS_PRESETS: ReadonlyArray<{ readonly value: number; readonly label: string }> = [
  { value: 30, label: '30 fps' },
  { value: 60, label: '60 fps' },
];

// OBS RecQuality tokens (case-sensitive). "Small" is tolerated if already stored but
// omitted from the picker. A stored value outside this list is preserved as a leading option.
const QUALITY_PRESETS: ReadonlyArray<{ readonly value: string; readonly label: string }> = [
  { value: 'Stream', label: 'Stream (smaller files)' },
  { value: 'HQ', label: 'High quality' },
  { value: 'Lossless', label: 'Lossless (huge files)' },
];

// OBS RecFormat2 containers. Restricted to the MP4 variants the in-app Chromium <video> can decode
// for jump-to-moment playback: mkv/mov record fine but won't preview in-app (no Matroska demuxer,
// flaky MOV), which silently breaks the headline feature. Both options here are crash-safe; plain
// `mp4` is omitted (unfinalized-file corruption risk on crash). Out-of-list stored value preserved.
const FORMAT_PRESETS: ReadonlyArray<{ readonly value: string; readonly label: string }> = [
  { value: 'hybrid_mp4', label: 'MP4 (hybrid)' },
  { value: 'fragmented_mp4', label: 'MP4 (fragmented)' },
];

// Floor for any drive cap (GB). There is no ceiling anymore — caps are free-form numbers,
// bounded only by the drive's real capacity (which the UI surfaces as a warning).
const CAP_MIN_GB = 10;

// Coerce a cap field's raw value into the positive integer the backend accepts. The core
// now rejects <=0 (a cleared field yields Number('')===0), so we never send a non-positive
// or fractional cap: blank/NaN/<=0 snaps up to CAP_MIN_GB and any fraction is rounded. Used
// both to reflect a sane value back into the field (onBlur) and to sanitize what we PUT.
function clampCapGb(value: number): number {
  if (!Number.isFinite(value) || value < CAP_MIN_GB) return CAP_MIN_GB;
  return Math.round(value);
}

// Bounds for the auto-clip padding (seconds). The core CLAMPS values outside this range
// to [1,60] (it does not 400); we also clamp client-side on blur/save so the field shows
// the value that will actually take effect (a cleared field reads as 0).
const PADDING_MIN_S = 1;
const PADDING_MAX_S = 60;

// Coerce the padding field's raw value into the bounded integer the backend accepts:
// blank/NaN/<1 snaps up to PADDING_MIN_S, anything past PADDING_MAX_S clamps down, and
// fractions are rounded. Used to reflect a sane value back (onBlur) and sanitize the PUT.
function clampPadding(value: number): number {
  if (!Number.isFinite(value) || value < PADDING_MIN_S) return PADDING_MIN_S;
  return Math.min(Math.round(value), PADDING_MAX_S);
}

// Human-readable size from a byte count (null -> em dash). TB once past 1024 GB.
function fmtSize(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined) return '—';
  const gb = bytes / 1024 ** 3;
  return gb >= 1024 ? `${(gb / 1024).toFixed(1)} TB` : `${Math.round(gb)} GB`;
}

// True when a configured cap can't be reached because the drive is too small: the cap
// exceeds what's physically attainable for our VODs (bytes we already store there + free
// space). Only meaningful once the drive has been saved and stat'd (freeBytes known).
function capExceedsDrive(capGb: number, usage: DriveUsage | undefined): boolean {
  if (!usage || usage.freeBytes === null) return false;
  const reachableBytes = usage.usedBytes + usage.freeBytes;
  return capGb * 1024 ** 3 > reachableBytes;
}

// Single-glyph icon per WASAPI kind, shown in each mixer row's chip (also keyed via [data-kind]).
const AUDIO_KIND_ICON: Record<AudioSourceKind, string> = {
  application: '◫',
  output: '🔊',
  input: '🎙',
};

const AUDIO_KIND_LABEL: Record<AudioSourceKind, string> = {
  application: 'Application',
  output: 'Output device',
  input: 'Microphone',
};

// Which mixer row a source renders as: the two built-ins (matched by reserved id) are fixed,
// non-removable rows; everything else is a removable application/app capture.
type MixerRowKind = 'microphone' | 'desktop' | 'app';

function mixerRowKind(src: AudioSource): MixerRowKind {
  if (src.id === BUILTIN_MICROPHONE_ID) return 'microphone';
  if (src.id === BUILTIN_DESKTOP_ID) return 'desktop';
  return 'app';
}

// Static presentation for the two always-present built-in rows. `dataKind` drives the chip tint.
const BUILTIN_ROW_META: Record<
  'microphone' | 'desktop',
  { readonly name: string; readonly desc: string; readonly dataKind: AudioSourceKind }
> = {
  microphone: {
    name: 'Microphone',
    desc: 'Your voice · default device',
    dataKind: 'input',
  },
  desktop: {
    name: 'Desktop audio',
    desc: 'All system sound — including Discord, browser, music',
    dataKind: 'output',
  },
};

// Single self-describing "Recorder" indicator. The recorder is app-managed now,
// so we deliberately avoid any OBS jargon; the prefix makes the chip readable on
// its own. Evaluated top-down, first match wins — connected is gated before the
// recording check so a dropped backend reads as an error, not a stale "recording".
function recorderStatusLabel(obs: StatusSnapshot['obs'] | null): {
  readonly text: string;
  readonly state: 'unknown' | 'error' | 'recording' | 'ready' | 'preparing';
} {
  if (obs === null) return { text: 'Recorder: connecting…', state: 'unknown' };
  if (!obs.connected) return { text: 'Recorder: error', state: 'error' };
  if (obs.recording) return { text: 'Recorder: recording', state: 'recording' };
  if (obs.sceneActive) return { text: 'Recorder: ready', state: 'ready' };
  return { text: 'Recorder: preparing', state: 'preparing' };
}

export function RecordingSettings({ obs }: RecordingSettingsProps): React.JSX.Element {
  const [loadState, setLoadState] = useState<LoadState>('loading');
  const [settings, setSettings] = useState<Settings | null>(null);

  // Editable form fields. resolution/videoDir/retention are user-set; encoder is
  // auto-probed and written back by the core, so it is shown read-only here.
  const [resolution, setResolution] = useState('');
  const [videoDir, setVideoDir] = useState('');
  const [retentionGb, setRetentionGb] = useState(50);
  const [accountId, setAccountId] = useState('');

  // Archive drives (tiered storage) and the live per-drive disk usage that backs the
  // free/total readout + the cap-exceeds-drive warning. usage is keyed by path at render.
  const [storageLocations, setStorageLocations] = useState<StorageLocation[]>([]);
  const [usage, setUsage] = useState<StorageUsage | null>(null);

  // Video controls (mirror `resolution`: saved now, applied on the next OBS launch).
  // encoderChoice maps 'auto' <-> '' (blank sentinel re-arms the GPU probe at boot).
  const [fps, setFps] = useState(60);
  const [quality, setQuality] = useState('HQ');
  const [recFormat, setRecFormat] = useState('hybrid_mp4');
  const [encoderChoice, setEncoderChoice] = useState('auto');

  // Auto-clip controls. autoClipOnRampage gates the rampage clipper; clipPaddingSeconds
  // is the lead/trail padding (clamped to [1,60] on blur/save like the storage caps).
  const [autoClipOnRampage, setAutoClipOnRampage] = useState(false);
  const [clipPaddingSeconds, setClipPaddingSeconds] = useState(8);

  // Latest polled OBS scene-preview frame (null = no frame / OBS down → placeholder).
  const [preview, setPreview] = useState<ScenePreview | null>(null);

  // The editable audio-source list and a per-kind cache of picker options. The core
  // seeds the list (we never synthesize a default here); the options cache is filled
  // lazily by loadInputs() and degrades to [] when OBS is down.
  const [audioSources, setAudioSources] = useState<AudioSource[]>([]);
  const [inputsByKind, setInputsByKind] = useState<Record<AudioSourceKind, AudioInputOption[]>>({
    application: [],
    output: [],
    input: [],
  });

  const [saveState, setSaveState] = useState<SaveState>('idle');
  const [error, setError] = useState<string | null>(null);
  // Per-archive-row validation messages, keyed by the row's stable id. A non-empty map
  // blocks the save (we never drop an invalid row silently) and renders an inline note
  // under the offending drive. Cleared on a clean save / re-edit.
  const [driveErrors, setDriveErrors] = useState<Record<string, string>>({});

  useEffect(() => {
    let cancelled = false;

    // Fetch and cache the picker options for one kind, ignoring failures (the core
    // already returns [] when OBS is down). Guards against the unmount flag so a
    // late resolve doesn't write into a torn-down component.
    const loadInputs = async (kind: AudioSourceKind): Promise<void> => {
      try {
        const opts = await fetchAudioInputs(kind);
        if (cancelled) return;
        setInputsByKind((prev) => ({ ...prev, [kind]: opts }));
      } catch {
        /* leave the cache as [] — the picker still shows the stored target */
      }
    };

    void (async (): Promise<void> => {
      try {
        const s = await fetchSettings();
        if (cancelled) return;
        setSettings(s);
        setResolution(s.resolution);
        setVideoDir(s.videoDir);
        setRetentionGb(s.retentionCapGb);
        setAccountId(s.accountId !== null ? String(s.accountId) : '');
        setStorageLocations(s.storageLocations);
        setAudioSources(s.audioSources);
        setFps(s.fps);
        setQuality(s.quality);
        setRecFormat(s.format);
        setEncoderChoice(s.encoder ? s.encoder : 'auto');
        setAutoClipOnRampage(s.autoClipOnRampage);
        setClipPaddingSeconds(s.clipPaddingSeconds);
        setLoadState('ready');
        // Prime each kind's options once the form is up.
        void loadInputs('application');
        void loadInputs('output');
        void loadInputs('input');
      } catch {
        if (cancelled) return;
        setLoadState('error');
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  // Live scene preview. Polls GET /obs/preview every ~1s while this tab is mounted
  // (the parent unmounts on navigation away, so the interval cleanup stops polling).
  // Each tick degrades to {dataUri:null} on failure → the UI shows the placeholder.
  useEffect(() => {
    let cancelled = false;
    // Skip a tick while the previous fetch is still in flight, so a slow/contended OBS screenshot
    // (up to the 5s fetch timeout) can't stack overlapping requests on the fixed 1s interval.
    let inFlight = false;

    const tick = async (): Promise<void> => {
      if (inFlight) return;
      inFlight = true;
      try {
        const next = await fetchScenePreview();
        if (!cancelled) setPreview(next);
      } catch {
        if (!cancelled) setPreview({ dataUri: null });
      } finally {
        inFlight = false;
      }
    };

    void tick();
    const id = window.setInterval(() => void tick(), 1000);
    return () => {
      cancelled = true;
      window.clearInterval(id);
    };
  }, []);

  // Per-drive disk usage backs the free/total readout and the cap-exceeds-drive warning.
  // Fetched on mount and re-fetched after a save (so a newly added drive gets stat'd).
  const refreshUsage = (): void => {
    void (async (): Promise<void> => {
      try {
        setUsage(await fetchStorageUsage());
      } catch {
        /* leave the prior usage; the readout degrades to em dashes */
      }
    })();
  };

  useEffect(() => {
    refreshUsage();
  }, []);

  // Refetch one kind's picker options on demand (e.g. when adding an application
  // source, whose process list is volatile). Best-effort; failures keep the cache.
  const refreshInputs = (kind: AudioSourceKind): void => {
    void (async (): Promise<void> => {
      try {
        const opts = await fetchAudioInputs(kind);
        setInputsByKind((prev) => ({ ...prev, [kind]: opts }));
      } catch {
        /* keep prior options */
      }
    })();
  };

  // A changed field marks the form dirty so Save is only offered when it matters.
  const dirty =
    settings !== null &&
    (resolution !== settings.resolution ||
      videoDir.trim() !== settings.videoDir ||
      retentionGb !== settings.retentionCapGb ||
      accountId.trim() !== (settings.accountId !== null ? String(settings.accountId) : '') ||
      fps !== settings.fps ||
      quality !== settings.quality ||
      recFormat !== settings.format ||
      (encoderChoice === 'auto' ? '' : encoderChoice) !== settings.encoder ||
      autoClipOnRampage !== settings.autoClipOnRampage ||
      clipPaddingSeconds !== settings.clipPaddingSeconds ||
      JSON.stringify(audioSources) !== JSON.stringify(settings.audioSources) ||
      JSON.stringify(storageLocations) !== JSON.stringify(settings.storageLocations));

  // The output folder only governs WHERE NEW recordings are written; existing VODs keep their stored
  // paths and stay where they are. Surface that as a reminder once the folder is actually changed.
  const folderChanged =
    settings !== null && videoDir.trim() !== '' && videoDir.trim() !== settings.videoDir;

  const onBrowse = async (): Promise<void> => {
    const picked = await window.dotarec?.selectFolder();
    if (picked) {
      setVideoDir(picked);
      setSaveState('idle');
    }
  };

  // ── Audio-source mutators. Each edits the list immutably and resets saveState to
  // 'idle' (matching the other onChange handlers) so Save re-arms after a tweak. ──

  // Add an application capture (the only add path in the mixer — the mic + desktop rows are
  // always present). Target is null until the user picks a running app from the row's picker.
  const addApp = (): void => {
    const source: AudioSource = {
      id: crypto.randomUUID(),
      kind: 'application',
      target: null,
      label: '',
      volume: 100,
      muted: false,
    };
    setAudioSources((prev) => [...prev, source]);
    // The running-process list is volatile — refetch so the new row's picker is current.
    refreshInputs('application');
    setSaveState('idle');
  };

  const removeAt = (i: number): void => {
    setAudioSources((prev) => prev.filter((_, idx) => idx !== i));
    setSaveState('idle');
  };

  const setTarget = (i: number, value: string, label: string): void => {
    setAudioSources((prev) =>
      prev.map((s, idx) => (idx === i ? { ...s, target: value, label } : s)),
    );
    setSaveState('idle');
  };

  const setVolume = (i: number, pct: number): void => {
    setAudioSources((prev) => prev.map((s, idx) => (idx === i ? { ...s, volume: pct } : s)));
    setSaveState('idle');
  };

  const toggleMute = (i: number): void => {
    setAudioSources((prev) =>
      prev.map((s, idx) => (idx === i ? { ...s, muted: !s.muted } : s)),
    );
    setSaveState('idle');
  };

  // ── Archive-drive mutators (tiered storage). Same immutable-edit + reset-saveState
  // shape as the audio-source mutators above. ──

  // Drop the inline validation note for one row once the user edits it, so a re-typed
  // path/cap re-arms the (blocked) save instead of leaving a stale "enter a folder" note.
  const clearDriveError = (id: string): void => {
    setDriveErrors((prev) => {
      if (!(id in prev)) return prev;
      const rest: Record<string, string> = {};
      for (const [key, msg] of Object.entries(prev)) {
        if (key !== id) rest[key] = msg;
      }
      return rest;
    });
  };

  const addDrive = (): void => {
    setStorageLocations((prev) => [
      ...prev,
      { id: crypto.randomUUID(), path: '', capGb: 500 },
    ]);
    setSaveState('idle');
  };

  const removeDrive = (i: number): void => {
    const removed = storageLocations[i];
    setStorageLocations((prev) => prev.filter((_, idx) => idx !== i));
    if (removed) clearDriveError(removed.id);
    setSaveState('idle');
  };

  const setDriveCap = (i: number, capGb: number): void => {
    const edited = storageLocations[i];
    setStorageLocations((prev) =>
      prev.map((d, idx) => (idx === i ? { ...d, capGb } : d)),
    );
    if (edited) clearDriveError(edited.id);
    setSaveState('idle');
  };

  const setDrivePath = (i: number, path: string): void => {
    const edited = storageLocations[i];
    setStorageLocations((prev) =>
      prev.map((d, idx) => (idx === i ? { ...d, path } : d)),
    );
    if (edited) clearDriveError(edited.id);
    setSaveState('idle');
  };

  const onBrowseDrive = async (i: number): Promise<void> => {
    const picked = await window.dotarec?.selectFolder();
    if (picked) {
      const edited = storageLocations[i];
      setStorageLocations((prev) =>
        prev.map((d, idx) => (idx === i ? { ...d, path: picked } : d)),
      );
      if (edited) clearDriveError(edited.id);
      setSaveState('idle');
    }
  };

  const onSave = async (): Promise<void> => {
    // Validate the archive rows BEFORE flipping into 'saving'. We never silently drop a
    // just-added, half-filled drive (the old behaviour filtered blank-path rows out while
    // still reporting "Saved"); instead we block the save and flag the offending row so the
    // user can finish or remove it. Cap is checked here too (clampCapGb fixes the wire value,
    // but a cleared field reads as 0 in the UI and we want an explicit message, not a silent bump).
    const nextDriveErrors: Record<string, string> = {};
    for (const d of storageLocations) {
      if (d.path.trim() === '') {
        nextDriveErrors[d.id] = 'Enter a folder for this archive drive.';
      } else if (!Number.isFinite(d.capGb) || d.capGb <= 0) {
        nextDriveErrors[d.id] = 'Cap must be greater than 0.';
      }
    }
    if (Object.keys(nextDriveErrors).length > 0) {
      setDriveErrors(nextDriveErrors);
      setSaveState('idle'); // not an 'error' state — the form is just incomplete, not failed
      return;
    }
    setDriveErrors({});

    // The output folder must not be blank: the core 400s a blank videoDir (OBS, thumbnails, and the
    // archiver would otherwise disagree about where recordings live), so surface a clear message here
    // instead of letting an empty field round-trip into a server error.
    if (videoDir.trim() === '') {
      setError('Choose an output folder for recordings.');
      setSaveState('error');
      return;
    }

    // A Dota account id is a 32-bit number (<=10 digits). The field strips non-digits but
    // doesn't bound length, so a long paste (e.g. a 17-digit SteamID64) coerces through
    // Number() to an imprecise float and would persist a WRONG id — silently mis-tagging
    // every death/kill. Reject anything that isn't a safe integer of sane length before we
    // build the patch, instead of letting the corrupted value round-trip.
    const trimmedAccount = accountId.trim();
    if (trimmedAccount !== '') {
      const parsedAccount = Number(trimmedAccount);
      if (trimmedAccount.length > 10 || !Number.isSafeInteger(parsedAccount)) {
        setError('Account ID looks invalid — enter your numeric Dota account ID, not a SteamID64.');
        setSaveState('error');
        return;
      }
    }

    setSaveState('saving');
    setError(null);

    const patch: SettingsPatch = {
      resolution: resolution.trim(),
      videoDir: videoDir.trim(),
      // Clamp to a positive integer: a cleared Max-storage field is Number('')===0, and the
      // core now 400s on <=0 — guard it client-side so we never send a non-positive cap.
      retentionCapGb: clampCapGb(retentionGb),
      // null means "leave unchanged" on the wire, so a blanked field clears via an explicit flag.
      ...(trimmedAccount === ''
        ? { clearAccountId: true }
        : { accountId: Number(trimmedAccount) }),
      // FULL-LIST REPLACE: always send the complete current array.
      audioSources,
      // FULL-LIST REPLACE too. All rows are validated non-blank above; clamp each cap to a
      // positive integer so a momentarily-cleared cap field can't slip a 0 onto the wire.
      storageLocations: storageLocations.map((d) => ({ ...d, capGb: clampCapGb(d.capGb) })),
      fps,
      quality,
      format: recFormat,
      // 'auto' <-> '' (blank): the blank sentinel re-arms the GPU probe on the next boot.
      encoder: encoderChoice === 'auto' ? '' : encoderChoice,
      autoClipOnRampage,
      // Clamp to [1,60]: a cleared field reads as 0; the core clamps too, but clamping here
      // keeps the UI honest about the value that will take effect.
      clipPaddingSeconds: clampPadding(clipPaddingSeconds),
    };

    try {
      const updated = await updateSettings(patch);
      setSettings(updated);
      setResolution(updated.resolution);
      setVideoDir(updated.videoDir);
      setRetentionGb(updated.retentionCapGb);
      setAccountId(updated.accountId !== null ? String(updated.accountId) : '');
      setStorageLocations(updated.storageLocations);
      setAudioSources(updated.audioSources);
      setFps(updated.fps);
      setQuality(updated.quality);
      setRecFormat(updated.format);
      setEncoderChoice(updated.encoder ? updated.encoder : 'auto');
      setAutoClipOnRampage(updated.autoClipOnRampage);
      setClipPaddingSeconds(updated.clipPaddingSeconds);
      setSaveState('saved');
      // Stat any newly added drive so its free/total + warning appear right after saving.
      refreshUsage();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save settings.');
      setSaveState('error');
    }
  };

  const status = recorderStatusLabel(obs);
  const activeUsage = usage?.drives.find((u) => u.role === 'active');
  const encoderToken = settings?.encoder ?? '';
  const resOptions = RES_PRESETS.some((p) => p.value === resolution)
    ? RES_PRESETS
    : [{ value: resolution, label: resolution || '—' }, ...RES_PRESETS];
  // Mirror resOptions: a stored quality/format outside the picker list (e.g. "Small"
  // or "ts") is preserved as a leading option so saving doesn't silently change it.
  const qualityOptions = QUALITY_PRESETS.some((p) => p.value === quality)
    ? QUALITY_PRESETS
    : [{ value: quality, label: quality || '—' }, ...QUALITY_PRESETS];
  const formatOptions = FORMAT_PRESETS.some((p) => p.value === recFormat)
    ? FORMAT_PRESETS
    : [{ value: recFormat, label: recFormat || '—' }, ...FORMAT_PRESETS];

  return (
    <section className="rec-panel" aria-label="Recording settings">
      <div className="rec-preview">
        {preview?.dataUri ? (
          <img className="rec-preview-img" src={preview.dataUri} alt="Live scene preview" />
        ) : (
          <div className="rec-preview-empty">OBS preview unavailable</div>
        )}
        <span className="rec-preview-badge" data-state={status.state}>
          {status.text}
        </span>
      </div>

      <header className="rec-panel-head">
        <h2 className="rec-panel-title">Recording</h2>
        <div className="rec-conn" data-state={status.state}>
          <span className="rec-conn-dot" data-state={status.state} aria-hidden="true" />
          <span className="rec-conn-text">{status.text}</span>
        </div>
      </header>

      {loadState === 'loading' && <p className="rec-muted">Loading settings…</p>}

      {loadState === 'error' && (
        <p className="rec-error" role="alert">
          Could not load settings from the core. Is it running?
        </p>
      )}

      {loadState === 'ready' && (
        <form
          className="rec-form"
          onSubmit={(e) => {
            e.preventDefault();
            void onSave();
          }}
        >
          <section className="rec-card">
            <h3 className="rec-sec">Output</h3>
            <div className="rec-row">
              <div className="rec-rowlabel">
                <label className="rec-label" htmlFor="rec-resolution">
                  Resolution
                </label>
                <p className="rec-desc">Canvas and output size of the recording.</p>
              </div>
              <div className="rec-control">
                <select
                  id="rec-resolution"
                  className="rec-select"
                  value={resolution}
                  onChange={(e) => {
                    setResolution(e.target.value);
                    setSaveState('idle');
                  }}
                >
                  {resOptions.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </div>
            </div>
          </section>

          <section className="rec-card">
            <h3 className="rec-sec">Video</h3>
            <div className="rec-row">
              <div className="rec-rowlabel">
                <label className="rec-label">
                  Encoder
                  {encoderChoice === 'auto' && <span className="rec-badge">auto</span>}
                </label>
                <p className="rec-desc">
                  Auto picks the best hardware encoder for your GPU. Override only if you
                  know which one you want.
                </p>
              </div>
              <div className="rec-control">
                <select
                  id="rec-encoder"
                  className="rec-select"
                  aria-label="Encoder"
                  value={encoderChoice}
                  onChange={(e) => {
                    setEncoderChoice(e.target.value);
                    setSaveState('idle');
                  }}
                >
                  <option value="auto">
                    Auto — {ENCODER_LABELS[encoderToken] ?? (encoderToken || 'detecting')}
                  </option>
                  {ENCODER_OVERRIDE_TOKENS.map((t) => (
                    <option key={t} value={t}>
                      {ENCODER_LABELS[t] ?? t}
                    </option>
                  ))}
                </select>
              </div>
            </div>
            <div className="rec-row">
              <div className="rec-rowlabel">
                <label className="rec-label" htmlFor="rec-fps">
                  Frame rate
                </label>
                <p className="rec-desc">Frames per second captured into the recording.</p>
              </div>
              <div className="rec-control">
                <select
                  id="rec-fps"
                  className="rec-select"
                  value={fps}
                  onChange={(e) => {
                    setFps(Number(e.target.value));
                    setSaveState('idle');
                  }}
                >
                  {FPS_PRESETS.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </div>
            </div>
            <div className="rec-row">
              <div className="rec-rowlabel">
                <label className="rec-label" htmlFor="rec-quality">
                  Quality
                </label>
                <p className="rec-desc">Higher quality means larger files.</p>
              </div>
              <div className="rec-control">
                <select
                  id="rec-quality"
                  className="rec-select"
                  value={quality}
                  onChange={(e) => {
                    setQuality(e.target.value);
                    setSaveState('idle');
                  }}
                >
                  {qualityOptions.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </div>
            </div>
            <div className="rec-row">
              <div className="rec-rowlabel">
                <label className="rec-label" htmlFor="rec-format">
                  Format
                </label>
                <p className="rec-desc">Recording container. All options are crash-safe.</p>
              </div>
              <div className="rec-control">
                <select
                  id="rec-format"
                  className="rec-select"
                  value={recFormat}
                  onChange={(e) => {
                    setRecFormat(e.target.value);
                    setSaveState('idle');
                  }}
                >
                  {formatOptions.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </div>
            </div>
          </section>

          <section className="rec-card">
            <h3 className="rec-sec">Auto-clip</h3>
            <div className="rec-row">
              <div className="rec-rowlabel">
                <span className="rec-label" id="rec-autoclip-label">
                  Auto-clip on rampage
                </span>
                <p className="rec-desc">
                  Automatically cut a short clip whenever you get a rampage.
                </p>
              </div>
              <div className="rec-control">
                <button
                  type="button"
                  className="rec-switch"
                  role="switch"
                  aria-checked={autoClipOnRampage}
                  aria-labelledby="rec-autoclip-label"
                  data-on={autoClipOnRampage ? 'true' : 'false'}
                  onClick={() => {
                    setAutoClipOnRampage((v) => !v);
                    setSaveState('idle');
                  }}
                >
                  <span className="rec-switch-knob" aria-hidden="true" />
                </button>
              </div>
            </div>
            <div className="rec-row">
              <div className="rec-rowlabel">
                <label className="rec-label" htmlFor="rec-clip-padding">
                  Clip padding
                </label>
                <p className="rec-desc">
                  Seconds of lead-in and trail kept around each auto-clip.
                </p>
              </div>
              <div className="rec-control rec-capfield">
                <input
                  id="rec-clip-padding"
                  className="rec-input rec-capinput"
                  type="number"
                  min={PADDING_MIN_S}
                  max={PADDING_MAX_S}
                  step={1}
                  value={clipPaddingSeconds}
                  onChange={(e) => {
                    // Keep the raw value while typing (so the field can be cleared and
                    // retyped); NaN is held as 0 and clamped to [1,60] on blur/save.
                    const v = Number(e.target.value);
                    setClipPaddingSeconds(Number.isFinite(v) ? v : 0);
                    setSaveState('idle');
                  }}
                  // Reflect a sensible value once the user leaves the field: a cleared/
                  // out-of-range value snaps into [1,60] rather than persisting (and sending) it.
                  onBlur={() => setClipPaddingSeconds((v) => clampPadding(v))}
                />
                <span className="rec-capunit">s</span>
              </div>
            </div>
          </section>

          <section className="rec-card">
            <h3 className="rec-sec">Storage</h3>
            <div className="rec-row">
              <div className="rec-rowlabel">
                <label className="rec-label" htmlFor="rec-videodir">
                  Output folder
                </label>
                <p className="rec-desc">Where recordings and thumbnails are written.</p>
              </div>
              <div className="rec-control rec-path">
                <input
                  id="rec-videodir"
                  className="rec-input rec-pathinput"
                  type="text"
                  value={videoDir}
                  autoComplete="off"
                  spellCheck={false}
                  placeholder="C:\Users\you\Videos\dota-recorder"
                  onChange={(e) => {
                    setVideoDir(e.target.value);
                    setSaveState('idle');
                  }}
                />
                <button
                  type="button"
                  className="rec-browse"
                  aria-label="Browse for the output folder"
                  onClick={() => void onBrowse()}
                >
                  Browse
                </button>
              </div>
            </div>
            {folderChanged && (
              <p className="rec-note" role="status">
                <span className="rec-note-icon" aria-hidden="true">
                  i
                </span>
                Existing recordings stay in their current folder — only new recordings will be saved
                here.
              </p>
            )}
            <div className="rec-row">
              <div className="rec-rowlabel">
                <label className="rec-label" htmlFor="rec-retention">
                  Max storage
                </label>
                <p className="rec-desc">
                  Disk budget for the recording drive. Oldest unstarred recordings are removed
                  first (across all drives).
                </p>
              </div>
              <div className="rec-control rec-capfield">
                <input
                  id="rec-retention"
                  className="rec-input rec-capinput"
                  type="number"
                  min={CAP_MIN_GB}
                  step={10}
                  value={retentionGb}
                  onChange={(e) => {
                    // Keep the raw value while typing (so the field can be cleared and
                    // retyped); NaN is held as 0 and snapped to the floor on blur/save.
                    const v = Number(e.target.value);
                    setRetentionGb(Number.isFinite(v) ? v : 0);
                    setSaveState('idle');
                  }}
                  // Reflect a sensible value once the user leaves the field: a cleared/<=0
                  // cap snaps up to the floor rather than persisting (and later sending) 0.
                  onBlur={() => setRetentionGb((v) => clampCapGb(v))}
                />
                <span className="rec-capunit">GB</span>
                {activeUsage && (
                  <span className="rec-capfree">
                    {fmtSize(activeUsage.freeBytes)} free of {fmtSize(activeUsage.totalBytes)}
                  </span>
                )}
              </div>
            </div>
            {capExceedsDrive(retentionGb, activeUsage) && (
              // role="alert" (assertive): a cap that can't be reached is an actionable
              // problem the user should hear immediately, not a passive status. The visible
              // "Warning:" prefix carries the meaning without relying on the gold colour
              // (the icon is aria-hidden, so it's the prefix that reaches a screen reader).
              <p className="rec-note rec-note-warn" role="alert">
                <span className="rec-note-icon" aria-hidden="true">
                  !
                </span>
                <strong className="rec-note-label">Warning:</strong> Cap {retentionGb} GB exceeds
                this drive — it will fill before the cap is reached.
              </p>
            )}
            {usage && (
              <p className="rec-note" role="status">
                <span className="rec-note-icon" aria-hidden="true">
                  i
                </span>
                Storing {fmtSize(usage.totalBytes)} of recordings across all drives —{' '}
                {fmtSize(usage.starredBytes)} starred (never auto-deleted).
              </p>
            )}
          </section>

          <section className="rec-card" aria-label="Archive drives">
            <h3 className="rec-sec">Archive drives</h3>
            <p className="rec-desc aud-intro">
              Finished recordings are moved off the recording drive onto these drives, filling each
              up to its cap. The newest matches stay on the fast recording drive. Leave empty to keep
              everything on the recording drive.
            </p>

            {storageLocations.map((loc, i) => {
              const u = usage?.drives.find((x) => x.role === 'archive' && x.path === loc.path);
              const warn = capExceedsDrive(loc.capGb, u);
              const rowError = driveErrors[loc.id];
              // Distinct accessible names so a screen-reader user can tell the (otherwise
              // identical) per-drive controls apart: prefer the entered path, fall back to
              // the 1-based row number for a still-blank drive.
              const driveName = loc.path.trim() !== '' ? loc.path.trim() : `drive ${i + 1}`;
              return (
                <div className="rec-row drv-row" key={loc.id}>
                  <div className="rec-rowlabel drv-path">
                    <div className="rec-control rec-path">
                      <input
                        className="rec-input rec-pathinput"
                        type="text"
                        value={loc.path}
                        autoComplete="off"
                        spellCheck={false}
                        placeholder="D:\dota-archive"
                        aria-label={`Folder for archive ${driveName}`}
                        onChange={(e) => setDrivePath(i, e.target.value)}
                      />
                      <button
                        type="button"
                        className="rec-browse"
                        aria-label={`Browse for archive ${driveName} folder`}
                        onClick={() => void onBrowseDrive(i)}
                      >
                        Browse
                      </button>
                    </div>
                    {u && (
                      <p className="rec-desc drv-free">
                        {fmtSize(u.usedBytes)} used · {fmtSize(u.freeBytes)} free of{' '}
                        {fmtSize(u.totalBytes)}
                      </p>
                    )}
                    {warn && (
                      // role="alert" + visible "Warning:" prefix — same rationale as the
                      // Max-storage warning above (assertive, not colour-only).
                      <p className="rec-note rec-note-warn" role="alert">
                        <span className="rec-note-icon" aria-hidden="true">
                          !
                        </span>
                        <strong className="rec-note-label">Warning:</strong> Cap {loc.capGb} GB
                        exceeds this drive — it will fill before the cap is reached.
                      </p>
                    )}
                    {rowError && (
                      // Save was blocked because this row is blank/invalid (FIX ii); keep the
                      // row visible and tell the user what to fix instead of silently dropping it.
                      <p className="rec-note rec-note-warn" role="alert">
                        <span className="rec-note-icon" aria-hidden="true">
                          !
                        </span>
                        <strong className="rec-note-label">Warning:</strong> {rowError}
                      </p>
                    )}
                  </div>
                  <div className="rec-control drv-control">
                    <div className="rec-capfield">
                      <input
                        className="rec-input rec-capinput"
                        type="number"
                        min={CAP_MIN_GB}
                        step={10}
                        aria-label={`Cap in GB for archive ${driveName}`}
                        value={loc.capGb}
                        onChange={(e) => {
                          // Keep the raw value while typing; clamp on blur/save so a
                          // momentarily-cleared field can't persist (or send) 0.
                          const v = Number(e.target.value);
                          setDriveCap(i, Number.isFinite(v) ? v : 0);
                        }}
                        onBlur={() => setDriveCap(i, clampCapGb(loc.capGb))}
                      />
                      <span className="rec-capunit">GB</span>
                    </div>
                    <button
                      type="button"
                      className="aud-remove"
                      aria-label={`Remove archive ${driveName}`}
                      title="Remove drive"
                      onClick={() => removeDrive(i)}
                    >
                      ✕
                    </button>
                  </div>
                </div>
              );
            })}

            <div className="rec-row aud-addrow">
              <div className="rec-rowlabel">
                <span className="rec-label">Add a drive</span>
                <p className="rec-desc">Use a larger drive to archive older recordings.</p>
              </div>
              <div className="rec-control aud-add">
                <button type="button" className="rec-browse aud-add-kind" onClick={addDrive}>
                  + Add drive
                </button>
              </div>
            </div>
          </section>

          <section className="rec-card" aria-label="Audio">
            <h3 className="rec-sec">Audio</h3>
            <p className="rec-desc aud-intro">
              Everything switched on here is mixed into your recording. Microphone and Desktop audio
              are off by default, so nothing is captured behind your back.
            </p>

            {audioSources.map((src, i) => {
              const row = mixerRowKind(src);
              const volId = `aud-vol-${src.id}`;
              // Display name for the row's accessible labels: the built-in name, or the app's label
              // (falling back to a generic word until one is picked).
              const rowName =
                row === 'app'
                  ? src.label || (src.kind === 'application' ? 'application' : 'device')
                  : BUILTIN_ROW_META[row].name;

              // Volume + On/Off cluster, identical for every row kind. The slider dims when the row is
              // off (muted) to reinforce the toggle, but stays adjustable so you can pre-set a level.
              const controls = (
                <>
                  <div className={`rec-slider aud-volume${src.muted ? ' aud-volume-off' : ''}`}>
                    <label className="aud-srlabel" htmlFor={volId}>
                      Volume
                    </label>
                    <input
                      id={volId}
                      className="rec-range"
                      type="range"
                      min={0}
                      max={100}
                      aria-label={`${rowName} volume`}
                      value={src.volume}
                      onChange={(e) => setVolume(i, Number(e.target.value))}
                    />
                    <span className="rec-rangeval aud-volval">{src.volume}%</span>
                  </div>
                  <button
                    type="button"
                    className="aud-mute"
                    data-muted={src.muted ? 'on' : 'off'}
                    aria-pressed={!src.muted}
                    aria-label={src.muted ? `Turn ${rowName} on` : `Turn ${rowName} off`}
                    title={src.muted ? `Turn ${rowName} on` : `Turn ${rowName} off`}
                    onClick={() => toggleMute(i)}
                  >
                    {src.muted ? 'Off' : 'On'}
                  </button>
                </>
              );

              // Built-in microphone / desktop rows: fixed name + description, no picker, no remove.
              if (row !== 'app') {
                const meta = BUILTIN_ROW_META[row];
                return (
                  <div className="rec-row aud-row" key={src.id}>
                    <div className="rec-rowlabel aud-meta">
                      <span
                        className="aud-kind"
                        data-kind={meta.dataKind}
                        aria-hidden="true"
                        title={meta.name}
                      >
                        {AUDIO_KIND_ICON[meta.dataKind]}
                      </span>
                      <div className="aud-rowtext">
                        <span className="rec-label">{meta.name}</span>
                        <p className="rec-desc aud-rowdesc">{meta.desc}</p>
                      </div>
                    </div>
                    <div className="rec-control aud-control">
                      {controls}
                      {/* Keep the right edge aligned with app rows, which have a remove button here. */}
                      <span className="aud-remove-spacer" aria-hidden="true" />
                    </div>
                  </div>
                );
              }

              // App-capture row: an app picker (the only dropdown left), volume, On/Off, and remove.
              // Surface an unknown stored target as a leading option so a previously-picked app that
              // isn't currently running still shows instead of silently resetting.
              const opts = inputsByKind[src.kind];
              const withDefault =
                src.kind === 'application' || opts.some((o) => o.value === 'default')
                  ? opts
                  : [{ value: 'default', label: 'Default' }, ...opts];
              const selectValue = src.target ?? '';
              const options =
                selectValue !== '' && !withDefault.some((o) => o.value === selectValue)
                  ? [{ value: selectValue, label: src.label || selectValue }, ...withDefault]
                  : withDefault;
              const targetId = `aud-target-${src.id}`;

              return (
                <div className="rec-row aud-row" key={src.id}>
                  <div className="rec-rowlabel aud-meta">
                    <span
                      className="aud-kind"
                      data-kind={src.kind}
                      aria-hidden="true"
                      title={AUDIO_KIND_LABEL[src.kind]}
                    >
                      {AUDIO_KIND_ICON[src.kind]}
                    </span>
                    <div className="aud-fields">
                      <label className="aud-srlabel" htmlFor={targetId}>
                        {src.kind === 'application' ? 'Application' : 'Device'}
                      </label>
                      <select
                        id={targetId}
                        className="rec-select aud-target"
                        value={selectValue}
                        onChange={(e) => {
                          const opt = options.find((o) => o.value === e.target.value);
                          setTarget(i, e.target.value, opt?.label ?? '');
                        }}
                      >
                        {src.kind === 'application' && selectValue === '' && (
                          <option value="">Select an application…</option>
                        )}
                        {options.map((o) => (
                          <option key={o.value} value={o.value}>
                            {o.label}
                          </option>
                        ))}
                      </select>
                      {src.target?.includes('dota2.exe') && (
                        <p className="rec-desc aud-rowdesc">
                          Removing this stops recording game audio.
                        </p>
                      )}
                    </div>
                  </div>
                  <div className="rec-control aud-control">
                    {controls}
                    <button
                      type="button"
                      className="aud-remove"
                      aria-label={`Remove ${rowName}`}
                      title="Remove source"
                      onClick={() => removeAt(i)}
                    >
                      ✕
                    </button>
                  </div>
                </div>
              );
            })}

            <div className="rec-row aud-addrow">
              <div className="rec-rowlabel">
                <span className="rec-label">Capture a specific app</span>
                <p className="rec-desc">Record just one program&apos;s sound, like Discord or Spotify.</p>
              </div>
              <div className="rec-control aud-add">
                <button type="button" className="rec-browse aud-add-kind" onClick={addApp}>
                  <span aria-hidden="true">＋</span> Add app
                </button>
              </div>
            </div>
          </section>

          <section className="rec-card">
            <h3 className="rec-sec">Account</h3>
            <div className="rec-row">
              <div className="rec-rowlabel">
                <label className="rec-label" htmlFor="rec-account">
                  Account ID
                  <span className="rec-badge rec-badge-muted">from GSI</span>
                </label>
                <p className="rec-desc">Tags your own deaths and kills. Auto-captured.</p>
              </div>
              <div className="rec-control">
                <input
                  id="rec-account"
                  className="rec-input"
                  type="text"
                  inputMode="numeric"
                  value={accountId}
                  autoComplete="off"
                  spellCheck={false}
                  placeholder="96828122"
                  onChange={(e) => {
                    setAccountId(e.target.value.replace(/\D/g, ''));
                    setSaveState('idle');
                  }}
                />
              </div>
            </div>
          </section>

          {error !== null && (
            <p className="rec-error" role="alert">
              {error}
            </p>
          )}

          <div className="rec-actions">
            <button
              className="rec-save"
              type="submit"
              disabled={saveState === 'saving' || !dirty}
            >
              {saveState === 'saving' ? 'Saving…' : 'Save changes'}
            </button>
            {saveState === 'saved' && !dirty && <span className="rec-saved">Saved</span>}
          </div>
        </form>
      )}
    </section>
  );
}
