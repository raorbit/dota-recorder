import {
  BUILTIN_DESKTOP_ID,
  BUILTIN_MICROPHONE_ID,
  type AudioInputOption,
  type AudioSource,
  type AudioSourceKind,
} from '../../../api/client';

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

interface AudioMixerSectionProps {
  readonly audioSources: AudioSource[];
  readonly inputsByKind: Record<AudioSourceKind, AudioInputOption[]>;
  readonly addApp: () => void;
  readonly removeAt: (i: number) => void;
  readonly setTarget: (i: number, value: string, label: string) => void;
  readonly setVolume: (i: number, pct: number) => void;
  readonly toggleMute: (i: number) => void;
}

export function AudioMixerSection({
  audioSources,
  inputsByKind,
  addApp,
  removeAt,
  setTarget,
  setVolume,
  toggleMute,
}: AudioMixerSectionProps): React.JSX.Element {
  return (
    <section className="rec-card" aria-label="Audio">
      <h3 className="rec-sec">Audio</h3>
      <p className="rec-desc aud-intro">
        Everything switched on here is mixed into your recording. Microphone and Desktop audio are
        off by default, so nothing is captured behind your back.
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
                  <p className="rec-desc aud-rowdesc">Removing this stops recording game audio.</p>
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
  );
}
