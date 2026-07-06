import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import './popup-menu.css';

// ── Popup menu primitive ──────────────────────────────────────────────────────
// A small floating menu rendered into document.body (the library Main column has
// overflow:hidden, which would otherwise clip it). A transparent backdrop catches a
// click-away; Escape closes; the panel is clamped to stay within the viewport. Keyboard
// operable per the ARIA menu pattern: focus moves into the panel on open, Up/Down/Home/End
// move between items, Tab is trapped within the panel, and focus returns to the opener on
// close. `role` is 'menu' for action menus (menuitem children) or 'group' for the column
// picker (a labelled checkbox group), so the role always matches the actual content.
// Shared by the match table (row actions, column picker, clip rows) and the video player
// (clip strip actions).
interface PopupMenuProps {
  readonly x: number;
  readonly y: number;
  readonly onClose: () => void;
  readonly ariaLabel: string;
  readonly role?: 'menu' | 'group';
  readonly children: React.ReactNode;
}

// The focusable, operable items inside a popup panel, in DOM order (menuitem buttons for
// the action menus; checkboxes for the column picker).
function popupItems(panel: HTMLElement | null): HTMLElement[] {
  if (!panel) return [];
  // Exactly the two popup item kinds: action-menu items and column-picker checkboxes. Kept narrow so
  // an incidental future child (a stray button / text input) can't slip into the roving-focus ring.
  return Array.from(
    panel.querySelectorAll<HTMLElement>('[role="menuitem"], input[type="checkbox"]'),
  );
}

export function PopupMenu({
  x,
  y,
  onClose,
  ariaLabel,
  role = 'menu',
  children,
}: PopupMenuProps): React.JSX.Element {
  const panelRef = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState({ x, y });

  // Clamp into the viewport once the panel's real size is known.
  useLayoutEffect(() => {
    const el = panelRef.current;
    if (!el) return;
    const { width, height } = el.getBoundingClientRect();
    const margin = 8;
    let nx = x;
    let ny = y;
    if (x + width > window.innerWidth - margin)
      nx = Math.max(margin, window.innerWidth - width - margin);
    if (y + height > window.innerHeight - margin)
      ny = Math.max(margin, window.innerHeight - height - margin);
    setPos({ x: nx, y: ny });
  }, [x, y]);

  // Move focus into the menu on open and restore it to the opener (the row / Columns button)
  // on close, so a keyboard user can operate the menu and lands back where they were.
  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    popupItems(panelRef.current)[0]?.focus();
    return () => opener?.focus?.();
  }, []);

  // Escape closes from anywhere (a window listener, so it works even before focus lands).
  useEffect(() => {
    const onKey = (e: KeyboardEvent): void => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  // Roving focus + Tab trap within the panel (Escape is handled by the window listener above).
  const onKeyDown = (e: React.KeyboardEvent): void => {
    const items = popupItems(panelRef.current);
    if (items.length === 0) return;
    const idx = items.indexOf(document.activeElement as HTMLElement);
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      items[(idx + 1 + items.length) % items.length]?.focus();
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      items[(idx - 1 + items.length) % items.length]?.focus();
    } else if (e.key === 'Home') {
      e.preventDefault();
      items[0]?.focus();
    } else if (e.key === 'End') {
      e.preventDefault();
      items[items.length - 1]?.focus();
    } else if (e.key === 'Tab') {
      e.preventDefault();
      const next = e.shiftKey ? (idx - 1 + items.length) % items.length : (idx + 1) % items.length;
      items[next]?.focus();
    }
  };

  return createPortal(
    <div
      className="ctx-backdrop"
      onMouseDown={onClose}
      onContextMenu={(e) => {
        e.preventDefault();
        onClose();
      }}
    >
      <div
        ref={panelRef}
        className="ctx-menu"
        role={role}
        aria-label={ariaLabel}
        style={{ left: pos.x, top: pos.y }}
        onMouseDown={(e) => e.stopPropagation()}
        onKeyDown={onKeyDown}
      >
        {children}
      </div>
    </div>,
    document.body,
  );
}
