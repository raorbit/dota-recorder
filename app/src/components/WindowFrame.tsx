import type { ReactNode } from 'react';
import './window-frame.css';

interface WindowFrameProps {
  readonly children: ReactNode;
}

// The 38px custom title bar + the app body beneath it. Mirrors the mockup's window
// chrome (section 07): a CSS-diamond logo mark on a red gradient, the wordmark, and
// the min / max / close window glyphs on the right.
//
// The control glyphs are visual placeholders for now — real window controls would
// route through the Electron main process. They are kept inert (non-interactive)
// so the chrome reads correctly without pretending to work.
export function WindowFrame({ children }: WindowFrameProps): React.JSX.Element {
  return (
    <div className="wf-root">
      <header className="wf-titlebar">
        <span className="wf-logo" aria-hidden="true">
          <span className="wf-logo-inner" />
        </span>
        <span className="wf-title">DOTA 2 RECORDER</span>
        <span className="wf-spacer" />
        <div className="wf-controls" aria-hidden="true">
          <span className="wf-ctl">&#x2013;</span>
          <span className="wf-ctl">&#x25A2;</span>
          <span className="wf-ctl wf-ctl-close">&#x2715;</span>
        </div>
      </header>
      <div className="wf-body">{children}</div>
    </div>
  );
}
