<#
.SYNOPSIS
  Save/restore KaloriKu app data across a debug uninstall or clean-install test.

.DESCRIPTION
  Uninstalling an APK deletes /data/data/id.kaloriku, which is where kaloriku.db
  (Room) and kaloriku_settings.preferences_pb (DataStore) live. Installing over
  the existing APK (`adb install -r`, i.e. `:phone:installDebug`) keeps them, so
  that is the normal workflow. Use this script only when a real uninstall is
  unavoidable, or when you deliberately want a clean install and then want the
  data back.

  Requires a debuggable build (`run-as` only works on debuggable apps).

.EXAMPLE
  ./tools/dev-data.ps1 save
  ./tools/dev-data.ps1 restore
  ./tools/dev-data.ps1 save -Serial emulator-5554
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory, Position = 0)]
    [ValidateSet('save', 'restore')]
    [string]$Action,

    [string]$Package = 'id.kaloriku',

    # adb serial, for when a watch and a phone are both attached.
    [string]$Serial,

    # Where save writes to / restore reads from. Gitignored.
    [string]$BackupDir = (Join-Path $PSScriptRoot '..' '.data-backup')
)

$ErrorActionPreference = 'Stop'

$adbArgs = @()
if ($Serial) { $adbArgs += @('-s', $Serial) }

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments)] [string[]]$Args)
    $out = & adb @($adbArgs + $Args) 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb $($Args -join ' ') failed:`n$out" }
    return $out
}

# `exec-out` emits raw bytes. Never route it through the PowerShell string
# pipeline or the database gets mangled; copy the process' stdout stream instead.
function Receive-AdbFile {
    param([string[]]$AdbArgs, [string]$DestPath)
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = 'adb'
    $psi.Arguments = ($AdbArgs -join ' ')
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $proc = [System.Diagnostics.Process]::Start($psi)
    $stream = [System.IO.File]::Create($DestPath)
    try {
        $proc.StandardOutput.BaseStream.CopyTo($stream)
    }
    finally {
        $stream.Dispose()
    }
    $proc.WaitForExit()
    if ($proc.ExitCode -ne 0) {
        Remove-Item -Force $DestPath -ErrorAction SilentlyContinue
        return $false
    }
    return $true
}

# Files that together make up the app's state. The -wal/-shm siblings matter:
# skipping them silently loses whatever is still in the write-ahead log.
$files = @(
    @{ Name = 'kaloriku.db';                          Path = 'databases/kaloriku.db';                                    Dest = 'kaloriku.db' },
    @{ Name = 'kaloriku.db-wal';                      Path = 'databases/kaloriku.db-wal';                                Dest = 'kaloriku.db-wal' },
    @{ Name = 'kaloriku.db-shm';                      Path = 'databases/kaloriku.db-shm';                                Dest = 'kaloriku.db-shm' },
    @{ Name = 'kaloriku_settings.preferences_pb';     Path = 'files/datastore/kaloriku_settings.preferences_pb';         Dest = 'kaloriku_settings.preferences_pb' }
)

if ($Action -eq 'save') {
    New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null

    # Checkpoint the WAL into the main db where possible, then stop the app so
    # nothing is mid-write while we copy.
    Invoke-Adb shell am force-stop $Package | Out-Null

    foreach ($f in $files) {
        $destPath = Join-Path $BackupDir $f.Dest
        $ok = Receive-AdbFile -AdbArgs ($adbArgs + @('exec-out', "run-as $Package cat $($f.Path)")) -DestPath $destPath
        if (-not $ok) {
            Write-Host "  skip  $($f.Name) (not present)"
            continue
        }
        Write-Host "  saved $($f.Name) -> $destPath ($((Get-Item $destPath).Length) bytes)"
    }
    Write-Host "`nBackup written to $BackupDir"
    Write-Host 'Tip: prefer `./gradlew :phone:installDebug` (adb install -r) so this is never needed.'
}
else {
    if (-not (Test-Path $BackupDir)) { throw "No backup at $BackupDir - run 'save' first." }

    Invoke-Adb shell am force-stop $Package | Out-Null

    foreach ($f in $files) {
        $srcPath = Join-Path $BackupDir $f.Dest
        if (-not (Test-Path $srcPath)) { Write-Host "  skip  $($f.Name) (not in backup)"; continue }

        $tmp = "/data/local/tmp/kaloriku-restore-$($f.Dest)"
        Invoke-Adb push $srcPath $tmp | Out-Null
        # Ensure the target directory exists (a fresh install always has it, but
        # be explicit) and keep the app's own uid ownership by copying as the app.
        Invoke-Adb shell run-as $Package mkdir -p (Split-Path $f.Path -Parent) | Out-Null
        Invoke-Adb shell "run-as $Package cp $tmp $($f.Path)" | Out-Null
        Invoke-Adb shell rm -f $tmp | Out-Null
        Write-Host "  restored $($f.Name)"
    }

    Invoke-Adb shell am force-stop $Package | Out-Null
    Write-Host "`nRestore complete. Relaunch the app to verify the log is back."
}
