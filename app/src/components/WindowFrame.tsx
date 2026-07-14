import type { ReactNode } from 'react';
import './window-frame.css';

interface WindowFrameProps {
  readonly children: ReactNode;
}

// The 38px custom title bar + the app body beneath it. Mirrors the mockup's window
// chrome (section 07): a CSS-diamond logo mark on a red gradient, the wordmark, and
// the min / max / close window glyphs on the right.
//
// The window is frameless (BrowserWindow frame: false), so these are the real
// controls, routed to the main process over the preload bridge. Close hides to the
// tray, matching the app's close-to-tray behavior. Outside Electron (plain browser
// dev) the bridge is absent and the buttons no-op.
export function WindowFrame({ children }: WindowFrameProps): React.JSX.Element {
  return (
    <div className="wf-root">
      <header className="wf-titlebar">
        <span className="wf-logo" aria-hidden="true">
          <span className="wf-logo-inner" />
        </span>
        <span className="wf-title">DOTA 2 RECORDER</span>
        <span className="wf-spacer" />
        <div className="wf-controls">
          <button
            type="button"
            className="wf-ctl"
            aria-label="Minimize"
            onClick={() => void window.dotarec?.minimizeWindow()}
          >
            &#x2013;
          </button>
          <button
            type="button"
            className="wf-ctl"
            aria-label="Maximize or restore"
            onClick={() => void window.dotarec?.maximizeToggleWindow()}
          >
            &#x25A2;
          </button>
          <button
            type="button"
            className="wf-ctl wf-ctl-close"
            aria-label="Close to tray"
            onClick={() => void window.dotarec?.closeWindow()}
          >
            &#x2715;
          </button>
        </div>
      </header>
      <div className="wf-body">{children}</div>
    </div>
  );
}
