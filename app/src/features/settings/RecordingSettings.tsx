import { useEffect, useState } from 'react';
import {
  fetchAudioInputs,
  fetchSettings,
  updateSettings,
  type AudioInputOption,
  type AudioSource,
  type AudioSourceKind,
  type Settings,
  type SettingsPatch,
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

const RETENTION_MIN = 10;
const RETENTION_MAX = 500;

// The three audio-source kinds offered by the "Add source" chooser, with a label
// and the single-glyph icon shown in each row's kind chip (also via [data-kind]).
const AUDIO_KINDS: ReadonlyArray<{
  readonly kind: AudioSourceKind;
  readonly label: string;
  readonly icon: string;
}> = [
  { kind: 'application', label: 'Application', icon: '◫' },
  { kind: 'output', label: 'Output device', icon: '🔊' },
  { kind: 'input', label: 'Microphone', icon: '🎙' },
];

const AUDIO_KIND_LABEL: Record<AudioSourceKind, string> = {
  application: 'Application',
  output: 'Output device',
  input: 'Microphone',
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
        setAudioSources(s.audioSources);
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
      JSON.stringify(audioSources) !== JSON.stringify(settings.audioSources));

  const onBrowse = async (): Promise<void> => {
    const picked = await window.dotarec?.selectFolder();
    if (picked) {
      setVideoDir(picked);
      setSaveState('idle');
    }
  };

  // ── Audio-source mutators. Each edits the list immutably and resets saveState to
  // 'idle' (matching the other onChange handlers) so Save re-arms after a tweak. ──

  const addSource = (kind: AudioSourceKind): void => {
    const source: AudioSource = {
      id: crypto.randomUUID(),
      kind,
      // application matches a process (null until a window is picked); output/input
      // default to the system default device.
      target: kind === 'application' ? null : 'default',
      label: '',
      volume: 100,
      muted: false,
    };
    setAudioSources((prev) => [...prev, source]);
    // The process/device list may have changed since mount — refetch for the new kind.
    refreshInputs(kind);
    setSaveState('idle');
  };

  const removeAt = (i: number): void => {
    setAudioSources((prev) => prev.filter((_, idx) => idx !== i));
    setSaveState('idle');
  };

  const setKind = (i: number, kind: AudioSourceKind): void => {
    setAudioSources((prev) =>
      prev.map((s, idx) =>
        idx === i
          ? { ...s, kind, target: kind === 'application' ? null : 'default', label: '' }
          : s,
      ),
    );
    refreshInputs(kind);
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

  const onSave = async (): Promise<void> => {
    setSaveState('saving');
    setError(null);

    const trimmedAccount = accountId.trim();
    const patch: SettingsPatch = {
      resolution: resolution.trim(),
      videoDir: videoDir.trim(),
      retentionCapGb: retentionGb,
      // null means "leave unchanged" on the wire, so a blanked field clears via an explicit flag.
      ...(trimmedAccount === ''
        ? { clearAccountId: true }
        : { accountId: Number(trimmedAccount) }),
      // FULL-LIST REPLACE: always send the complete current array.
      audioSources,
    };

    try {
      const updated = await updateSettings(patch);
      setSettings(updated);
      setResolution(updated.resolution);
      setVideoDir(updated.videoDir);
      setRetentionGb(updated.retentionCapGb);
      setAccountId(updated.accountId !== null ? String(updated.accountId) : '');
      setAudioSources(updated.audioSources);
      setSaveState('saved');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save settings.');
      setSaveState('error');
    }
  };

  const status = recorderStatusLabel(obs);
  const encoderToken = settings?.encoder ?? '';
  const encoderLabel = ENCODER_LABELS[encoderToken] ?? (encoderToken || 'Detecting…');
  const resOptions = RES_PRESETS.some((p) => p.value === resolution)
    ? RES_PRESETS
    : [{ value: resolution, label: resolution || '—' }, ...RES_PRESETS];

  return (
    <section className="rec-panel" aria-label="Recording settings">
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
                  <span className="rec-badge">auto</span>
                </label>
                <p className="rec-desc">Best hardware encoder, detected for your GPU.</p>
              </div>
              <div className="rec-control">
                <div className="rec-readonly" title={encoderToken}>
                  {encoderLabel}
                </div>
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
                  onClick={() => void onBrowse()}
                >
                  Browse
                </button>
              </div>
            </div>
            <div className="rec-row">
              <div className="rec-rowlabel">
                <label className="rec-label" htmlFor="rec-retention">
                  Max storage
                </label>
                <p className="rec-desc">Oldest unstarred recordings are removed first.</p>
              </div>
              <div className="rec-control rec-slider">
                <input
                  id="rec-retention"
                  className="rec-range"
                  type="range"
                  min={RETENTION_MIN}
                  max={RETENTION_MAX}
                  step={10}
                  value={retentionGb}
                  onChange={(e) => {
                    setRetentionGb(Number(e.target.value));
                    setSaveState('idle');
                  }}
                />
                <span className="rec-rangeval">{retentionGb} GB</span>
              </div>
            </div>
          </section>

          <section className="rec-card" aria-label="Audio sources">
            <h3 className="rec-sec">Audio sources</h3>
            <p className="rec-desc aud-intro">
              Each source is captured into the recording. Add your speakers, a
              microphone, or a specific app like Discord.
            </p>

            {audioSources.map((src, i) => {
              // Option list for this row's kind; ensure output/input always offer
              // "Default", and surface an unknown stored target as a leading option
              // so a picked device that's not currently enumerable still shows.
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
              const kindMeta = AUDIO_KINDS.find((k) => k.kind === src.kind);
              const targetId = `aud-target-${src.id}`;
              const volId = `aud-vol-${src.id}`;

              return (
                <div className="rec-row aud-row" key={src.id}>
                  <div className="rec-rowlabel aud-meta">
                    <span
                      className="aud-kind"
                      data-kind={src.kind}
                      aria-hidden="true"
                      title={AUDIO_KIND_LABEL[src.kind]}
                    >
                      {kindMeta?.icon ?? '♪'}
                    </span>
                    <div className="aud-fields">
                      <label className="rec-label aud-kind-label" htmlFor={`aud-kind-${src.id}`}>
                        {AUDIO_KIND_LABEL[src.kind]}
                      </label>
                      <select
                        id={`aud-kind-${src.id}`}
                        className="rec-select aud-kind-select"
                        aria-label="Source kind"
                        value={src.kind}
                        onChange={(e) => setKind(i, e.target.value as AudioSourceKind)}
                      >
                        {AUDIO_KINDS.map((k) => (
                          <option key={k.kind} value={k.kind}>
                            {k.label}
                          </option>
                        ))}
                      </select>
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
                    </div>
                  </div>
                  <div className="rec-control aud-control">
                    <div className="rec-slider aud-volume">
                      <label className="aud-srlabel" htmlFor={volId}>
                        Volume
                      </label>
                      <input
                        id={volId}
                        className="rec-range"
                        type="range"
                        min={0}
                        max={100}
                        value={src.volume}
                        onChange={(e) => setVolume(i, Number(e.target.value))}
                      />
                      <span className="rec-rangeval aud-volval">{src.volume}%</span>
                    </div>
                    <button
                      type="button"
                      className="aud-mute"
                      data-muted={src.muted ? 'on' : 'off'}
                      aria-pressed={src.muted}
                      aria-label={src.muted ? 'Unmute source' : 'Mute source'}
                      title={src.muted ? 'Unmute' : 'Mute'}
                      onClick={() => toggleMute(i)}
                    >
                      {src.muted ? 'Muted' : 'On'}
                    </button>
                    <button
                      type="button"
                      className="aud-remove"
                      aria-label="Remove source"
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
                <span className="rec-label">Add a source</span>
                <p className="rec-desc">Capture another device or application.</p>
              </div>
              <div className="rec-control aud-add">
                {AUDIO_KINDS.map((k) => (
                  <button
                    key={k.kind}
                    type="button"
                    className="rec-browse aud-add-kind"
                    onClick={() => addSource(k.kind)}
                  >
                    <span aria-hidden="true">{k.icon}</span> {k.label}
                  </button>
                ))}
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
