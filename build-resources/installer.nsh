; Custom NSIS install/uninstall hooks for Dota 2 Recorder (electron-builder nsis.include).
;
; Scopes the managed OBS control port (obs-websocket, TCP 4466) to loopback with a Windows
; Firewall inbound BLOCK rule -- issue #14. obs-websocket binds all interfaces (0.0.0.0) and
; exposes no bind-address option; Windows never filters loopback traffic, so a block rule on the
; port blocks the LAN only, while the app's own 127.0.0.1 client keeps working.
;
; Firewall edits require admin, so the rule is added through an elevated (UAC) step -- this is the
; RELIABLE counterpart to the supervisor's best-effort runtime rule (obs-supervisor.ts). It is
; non-fatal: if the user declines elevation the install still completes, and obs-websocket auth
; (auth_required + a 144-bit password) plus the supervisor's best-effort rule still protect the port.
;
; The two netsh calls are chained in a single elevated cmd (delete-any-stale then add) so install
; raises at most one UAC prompt.
;
; Both macros are skipped when the installer/uninstaller runs with --updated (electron-updater's
; silent auto-update path; ${isUpdated} is electron-builder's generated LogicLib predicate for
; that flag). During an update the OLD version's uninstaller is re-run with --updated before the
; new files land, so without these guards every auto-update would raise two UAC prompts (the
; "runas" verb shows the UAC dialog even under /S — SW_HIDE only hides the cmd window) and
; ExecShellWait blocks the silent installer until each dialog is answered. The rule needs no
; per-update churn anyway: it is keyed to the port, not the install path, so it stays valid
; across updates and is only added on a real install and removed on a real uninstall.

!macro customInstall
  ${ifNot} ${isUpdated}
    DetailPrint "Scoping the OBS control port (4466) to loopback (may prompt for administrator)..."
    ExecShellWait "runas" "cmd.exe" '/c netsh advfirewall firewall delete rule name="Dota 2 Recorder OBS WebSocket" protocol=TCP localport=4466 & netsh advfirewall firewall add rule name="Dota 2 Recorder OBS WebSocket" dir=in action=block protocol=TCP localport=4466' SW_HIDE
  ${endIf}
!macroend

!macro customUnInstall
  ${ifNot} ${isUpdated}
    DetailPrint "Removing the OBS control port firewall rule..."
    ExecShellWait "runas" "cmd.exe" '/c netsh advfirewall firewall delete rule name="Dota 2 Recorder OBS WebSocket" protocol=TCP localport=4466' SW_HIDE
  ${endIf}
!macroend
