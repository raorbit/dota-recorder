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
; Every program these hooks start is named by its full path. Windows can find a bare name in the
; installer's own folder (Downloads, for a downloaded installer) or the folder it works in (the
; install folder, which the user can write to) before the system's, so anything the user ran could
; have its own cmd.exe or netsh.exe started there, elevated. After /c, cmd drops the first and last
; quote of the line it is given, which is why each line is wrapped in one more pair.
;
; The elevated cmd also gets /d, which skips the AutoRun commands cmd otherwise runs from the
; registry before anything else. Anything the user runs can set HKCU's, and without /d cmd would run
; it with the administrator rights the prompt just granted, before either netsh call. /d doesn't
; change how cmd reads the quotes after /c. scripts/check-packaging-config.mjs fails CI on a program
; these hooks start by a bare name, or a cmd they start without /d; its comments list the ways of
; starting a program it reads.
;
; Both rules cover these hooks, not the rest of the installer, which is electron-builder's: the
; check doesn't read it, and it isn't ours to change. Its check for a running app, for one, starts
; PowerShell without -NoProfile, or cmd without /d running tasklist and taskkill by bare names, and
; in an install or uninstall for every user it can run elevated.
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
    ExecShellWait "runas" "$SYSDIR\cmd.exe" '/d /c ""$SYSDIR\netsh.exe" advfirewall firewall delete rule name="Dota 2 Recorder OBS WebSocket" protocol=TCP localport=4466 & "$SYSDIR\netsh.exe" advfirewall firewall add rule name="Dota 2 Recorder OBS WebSocket" dir=in action=block protocol=TCP localport=4466"' SW_HIDE
  ${endIf}
!macroend

!macro customUnInstall
  ${ifNot} ${isUpdated}
    DetailPrint "Removing the OBS control port firewall rule..."
    ExecShellWait "runas" "$SYSDIR\cmd.exe" '/d /c ""$SYSDIR\netsh.exe" advfirewall firewall delete rule name="Dota 2 Recorder OBS WebSocket" protocol=TCP localport=4466"' SW_HIDE
  ${endIf}
!macroend
