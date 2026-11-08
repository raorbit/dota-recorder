<#
.SYNOPSIS
Prints the memory footprint of a running Dota 2 Recorder, process by process.

.DESCRIPTION
Walks the process tree under the app's main process -- the Electron main, its renderer / GPU /
utility helpers, the javaw core and (while Dota runs) obs64 -- and prints each one's working set
(what Task Manager's Memory column shows), private commit, thread count and role, plus totals.
Read-only: it never touches the processes.

Working set is what the user feels; commit is what the processes have asked for. A long-idle
process's working set shrinks as Windows trims it, so compare like with like (fresh vs fresh, or
idle-for-hours vs idle-for-hours).

.PARAMETER ProcessName
Image name (without .exe) of the app's main process. Default: the installed app. Use 'electron'
for an `npm run dev` session.

.EXAMPLE
powershell -NoProfile -File scripts/measure-footprint.ps1

.EXAMPLE
powershell -NoProfile -File scripts/measure-footprint.ps1 -ProcessName electron
#>
param(
  [string]$ProcessName = 'Dota 2 Recorder'
)

$all = Get-CimInstance Win32_Process

# Electron's helper processes share the exe name; the main process is the one without --type=.
$roots = @($all | Where-Object { $_.Name -eq "$ProcessName.exe" -and $_.CommandLine -notmatch '--type=' })
if ($roots.Count -eq 0) {
  Write-Error "No running '$ProcessName.exe' main process found."
  exit 1
}

function Get-Role($p, $rootId) {
  if ($p.ProcessId -eq $rootId) { return 'electron main' }
  switch -regex ($p.Name) {
    '^javaw?\.exe$' { return 'jvm core' }
    '^obs64\.exe$'  { return 'obs' }
    '^ffmpeg\.exe$' { return 'ffmpeg' }
  }
  if ($p.CommandLine -match '--utility-sub-type=\w+\.mojom\.(\w+)') { return "utility ($($Matches[1]))" }
  if ($p.CommandLine -match '--type=([a-z-]+)') { return $Matches[1] }
  return $p.Name
}

foreach ($root in $roots) {
  # Collect the whole tree (children of children: OBS and javaw hang off main; ffmpeg off javaw).
  $ids = @($root.ProcessId)
  do {
    $before = $ids.Count
    foreach ($p in $all) {
      if (($ids -contains $p.ParentProcessId) -and -not ($ids -contains $p.ProcessId)) { $ids += $p.ProcessId }
    }
  } while ($ids.Count -ne $before)

  $rows = $all | Where-Object { $ids -contains $_.ProcessId } | ForEach-Object {
    [pscustomobject]@{
      Role      = Get-Role $_ $root.ProcessId
      PID       = $_.ProcessId
      'WS MB'   = [math]::Round($_.WorkingSetSize / 1MB, 1)
      'Commit MB' = [math]::Round($_.PrivatePageCount / 1MB, 1)
      Threads   = $_.ThreadCount
    }
  } | Sort-Object 'WS MB' -Descending

  $dota = Get-Process dota2 -ErrorAction SilentlyContinue
  Write-Output ("{0} (pid {1}) started {2}; Dota {3}" -f $root.Name, $root.ProcessId,
    (Get-Process -Id $root.ProcessId).StartTime, $(if ($dota) { 'running' } else { 'not running' }))
  $rows | Format-Table -AutoSize | Out-String | Write-Output
  Write-Output ("TOTAL: {0} processes, {1:N1} MB working set, {2:N1} MB commit, {3} threads" -f
    $rows.Count, ($rows | Measure-Object 'WS MB' -Sum).Sum, ($rows | Measure-Object 'Commit MB' -Sum).Sum,
    ($rows | Measure-Object Threads -Sum).Sum)
}
