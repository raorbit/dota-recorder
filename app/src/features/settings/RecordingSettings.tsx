import { useEffect, useState } from 'react';
import {
  fetchSettings,
  updateSettings,
  type AudioSource,
  type Settings,
  type SettingsPatch,
  type StorageLocation,
} from '../../api/client';
import type { StatusSnapshot } from '../../api/client';
import { buildAccountPatch } from '../../lib/account-patch';
import {
  PADDING_MAX_S,
  PADDING_MIN_S,
  RES_PRESETS,
  clampCapGb,
  clampPadding,
  withStoredOption,
} from './recording/settings-helpers';
import type { SaveState } from './recording/settings-helpers';
import { useAudioInputs } from './recording/useAudioInputs';
import { useScenePreview } from './recording/useScenePreview';
import { useStorageUsage } from './recording/useStorageUsage';
import { ArchiveDrivesSection } from './recording/ArchiveDrivesSection';
import { AudioMixerSection } from './recording/AudioMixerSection';
import { ScenePreview } from './recording/ScenePreview';
import { StorageSection } from './recording/StorageSection';
import { VideoSection } from './recording/VideoSection';
import './recording-settings.css';

type LoadState = 'loading' | 'ready' | 'error';

interface RecordingSettingsProps {
  // Live recorder status, lifted from App's StatusSocket. Null until the first
  // frame (or while the core is unreachable) so we can render an "unknown" state
  // rather than a misleading error.
  readonly obs: StatusSnapshot['obs'] | null;
}

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
  // Whether the user edited the Account ID field this session. The core auto-captures
  // the id from GSI after this form loads, so an untouched (still-stale) field must be
  // left out of the PUT — otherwise an unrelated save would clobber the captured id.
  // Reset on load and after each save; see buildAccountPatch.
  const [accountTouched, setAccountTouched] = useState(false);

  // Archive drives (tiered storage). The live per-drive disk usage that backs the
  // free/total readout + the cap-exceeds-drive warning lives in useStorageUsage.
  const [storageLocations, setStorageLocations] = useState<StorageLocation[]>([]);

  // Video controls (mirror `resolution`: saved now, applied on the next OBS launch).
  // encoderChoice maps 'auto' <-> '' (blank sentinel re-arms the GPU probe at boot).
  const [fps, setFps] = useState(30);
  const [quality, setQuality] = useState('Stream');
  const [recFormat, setRecFormat] = useState('hybrid_mp4');
  const [encoderChoice, setEncoderChoice] = useState('auto');

  // Auto-clip controls. autoClipOnRampage gates the rampage clipper; clipPaddingSeconds
  // is the lead/trail padding (clamped to [1,60] on blur/save like the storage caps).
  const [autoClipOnRampage, setAutoClipOnRampage] = useState(false);
  const [clipPaddingSeconds, setClipPaddingSeconds] = useState(8);

  // Whether Hero Demo sessions are recorded too. Off by default (real matches only).
  const [recordDemoMatches, setRecordDemoMatches] = useState(false);

  // The editable audio-source list. The core seeds it (we never synthesize a default
  // here); the per-kind picker-options cache lives in useAudioInputs.
  const [audioSources, setAudioSources] = useState<AudioSource[]>([]);

  // Polling/derived state lifted into hooks. The scene preview polls on its own; usage
  // and audio-input options are primed after the settings load resolves below.
  const preview = useScenePreview();
  const { usage, refreshUsage } = useStorageUsage();
  const { inputsByKind, refreshInputs, primeAll } = useAudioInputs();

  const [saveState, setSaveState] = useState<SaveState>('idle');
  const [error, setError] = useState<string | null>(null);
  // Per-archive-row validation messages, keyed by the row's stable id. A non-empty map
  // blocks the save (we never drop an invalid row silently) and renders an inline note
  // under the offending drive. Cleared on a clean save / re-edit.
  const [driveErrors, setDriveErrors] = useState<Record<string, string>>({});

  useEffect(() => {
    let cancelled = false;

    void (async (): Promise<void> => {
      try {
        const s = await fetchSettings();
        if (cancelled) return;
        setSettings(s);
        setResolution(s.resolution);
        setVideoDir(s.videoDir);
        setRetentionGb(s.retentionCapGb);
        setAccountId(s.accountId !== null ? String(s.accountId) : '');
        setAccountTouched(false);
        setStorageLocations(s.storageLocations);
        setAudioSources(s.audioSources);
        setFps(s.fps);
        setQuality(s.quality);
        setRecFormat(s.format);
        setEncoderChoice(s.encoder ? s.encoder : 'auto');
        setAutoClipOnRampage(s.autoClipOnRampage);
        setClipPaddingSeconds(s.clipPaddingSeconds);
        setRecordDemoMatches(s.recordDemoMatches);
        setLoadState('ready');
        // Ordering matters: settings load first, THEN prime the audio-input kinds.
        primeAll();
      } catch {
        if (cancelled) return;
        setLoadState('error');
      }
    })();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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
      recordDemoMatches !== settings.recordDemoMatches ||
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
    setAudioSources((prev) => prev.map((s, idx) => (idx === i ? { ...s, muted: !s.muted } : s)));
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
    setStorageLocations((prev) => [...prev, { id: crypto.randomUUID(), path: '', capGb: 500 }]);
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
    setStorageLocations((prev) => prev.map((d, idx) => (idx === i ? { ...d, capGb } : d)));
    if (edited) clearDriveError(edited.id);
    setSaveState('idle');
  };

  const setDrivePath = (i: number, path: string): void => {
    const edited = storageLocations[i];
    setStorageLocations((prev) => prev.map((d, idx) => (idx === i ? { ...d, path } : d)));
    if (edited) clearDriveError(edited.id);
    setSaveState('idle');
  };

  const onBrowseDrive = async (i: number): Promise<void> => {
    const picked = await window.dotarec?.selectFolder();
    if (picked) {
      const edited = storageLocations[i];
      setStorageLocations((prev) => prev.map((d, idx) => (idx === i ? { ...d, path: picked } : d)));
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
    // build the patch, instead of letting the corrupted value round-trip. Only the value
    // the user actually typed is sent (see buildAccountPatch), so only validate that.
    const trimmedAccount = accountId.trim();
    if (accountTouched && trimmedAccount !== '') {
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
      // Only carry the account id when the user actually edited the field this session.
      // An untouched field is omitted so an unrelated save can't clobber the id the core
      // auto-captured from GSI after this form loaded (which would break tagging until a
      // core restart, since SettingsController latches accountCaptureDone).
      ...buildAccountPatch({
        touched: accountTouched,
        value: accountId,
        baseline: settings?.accountId ?? null,
      }),
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
      recordDemoMatches,
    };

    try {
      const updated = await updateSettings(patch);
      setSettings(updated);
      setResolution(updated.resolution);
      setVideoDir(updated.videoDir);
      setRetentionGb(updated.retentionCapGb);
      setAccountId(updated.accountId !== null ? String(updated.accountId) : '');
      setAccountTouched(false);
      setStorageLocations(updated.storageLocations);
      setAudioSources(updated.audioSources);
      setFps(updated.fps);
      setQuality(updated.quality);
      setRecFormat(updated.format);
      setEncoderChoice(updated.encoder ? updated.encoder : 'auto');
      setAutoClipOnRampage(updated.autoClipOnRampage);
      setClipPaddingSeconds(updated.clipPaddingSeconds);
      setRecordDemoMatches(updated.recordDemoMatches);
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
  const resOptions = withStoredOption(RES_PRESETS, resolution);

  return (
    <section className="rec-panel" aria-label="Recording settings">
      <ScenePreview preview={preview} status={status} />

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
            <h3 className="rec-sec">Capture</h3>
            <div className="rec-row">
              <div className="rec-rowlabel">
                <span className="rec-label" id="rec-demo-label">
                  Record demo matches
                </span>
                <p className="rec-desc">
                  Also record Hero Demo sessions, not just real matches.
                </p>
              </div>
              <div className="rec-control">
                <button
                  type="button"
                  className="rec-switch"
                  role="switch"
                  aria-checked={recordDemoMatches}
                  aria-labelledby="rec-demo-label"
                  data-on={recordDemoMatches ? 'true' : 'false'}
                  onClick={() => {
                    setRecordDemoMatches((v) => !v);
                    setSaveState('idle');
                  }}
                >
                  <span className="rec-switch-knob" aria-hidden="true" />
                </button>
              </div>
            </div>
          </section>

          <VideoSection
            encoderChoice={encoderChoice}
            setEncoderChoice={setEncoderChoice}
            encoderToken={encoderToken}
            fps={fps}
            setFps={setFps}
            quality={quality}
            setQuality={setQuality}
            recFormat={recFormat}
            setRecFormat={setRecFormat}
            setSaveState={setSaveState}
          />

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
                <p className="rec-desc">Seconds of lead-in and trail kept around each auto-clip.</p>
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

          <StorageSection
            videoDir={videoDir}
            setVideoDir={setVideoDir}
            retentionGb={retentionGb}
            setRetentionGb={setRetentionGb}
            usage={usage}
            activeUsage={activeUsage}
            folderChanged={folderChanged}
            onBrowse={onBrowse}
            setSaveState={setSaveState}
          />

          <ArchiveDrivesSection
            storageLocations={storageLocations}
            usage={usage}
            driveErrors={driveErrors}
            addDrive={addDrive}
            removeDrive={removeDrive}
            setDriveCap={setDriveCap}
            setDrivePath={setDrivePath}
            onBrowseDrive={onBrowseDrive}
          />

          <AudioMixerSection
            audioSources={audioSources}
            inputsByKind={inputsByKind}
            addApp={addApp}
            removeAt={removeAt}
            setTarget={setTarget}
            setVolume={setVolume}
            toggleMute={toggleMute}
          />

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
                  placeholder="auto-filled from your first match"
                  onChange={(e) => {
                    setAccountId(e.target.value.replace(/\D/g, ''));
                    setAccountTouched(true);
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
            <button className="rec-save" type="submit" disabled={saveState === 'saving' || !dirty}>
              {saveState === 'saving' ? 'Saving…' : 'Save changes'}
            </button>
            {saveState === 'saved' && !dirty && <span className="rec-saved">Saved</span>}
          </div>
        </form>
      )}
    </section>
  );
}
