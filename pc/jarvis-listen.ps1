<#
  Jarvis PC remote listener for Windows (Windows PowerShell 5.1, nothing to install).

  jarvis-listen.ps1 -Pair              paste the pairing code from the app (Settings > PC link > PAIR PC)
  jarvis-listen.ps1                    listen for commands from the phone (keep it running)
  jarvis-listen.ps1 -InstallStartup    start it hidden every time you log in (and start it now)
  jarvis-listen.ps1 -UninstallStartup  stop starting it at login
  jarvis-listen.ps1 -Stop              stop the running listener
  jarvis-listen.ps1 -ShowConfig        show the config file path and which commands are switched on

  Security: the phone sends only a command NAME, signed with HMAC-SHA256 using a secret that only the phone and
  this PC know. Wrong signatures, messages older than 60 seconds and repeated messages are ignored. Only commands
  switched on in %USERPROFILE%\.jarvis-pc.json run; shutdown, restart, sleep and your own commands are OFF until
  you change false to true there. Never share or upload .jarvis-pc.json (it holds the secret).
#>
param(
    [switch]$Pair,
    [string]$Code = "",
    [switch]$InstallStartup,
    [switch]$UninstallStartup,
    [switch]$Stop,
    [switch]$ShowConfig,
    [string]$Child = "",     # internal: watch | shutdownwhendone | run
    [string]$Target = ""     # internal: process or command name for -Child
)

$ErrorActionPreference = "Stop"
$ConfigFile = Join-Path $HOME ".jarvis-pc.json"
$NonceFile = Join-Path $HOME ".jarvis-pc.nonces"
$LogFile = Join-Path $HOME ".jarvis-pc.log"
$NameRe = '^[A-Za-z0-9._ -]{0,64}$'
$MaxSkew = 60
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

function Write-Log([string]$m) {
    try { Add-Content -Path $LogFile -Value ("{0} {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $m) -Encoding UTF8 } catch { }
}
function Get-Now { [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() }

function Get-Mac([string]$data) {
    $h = New-Object System.Security.Cryptography.HMACSHA256
    $h.Key = [Text.Encoding]::UTF8.GetBytes($script:Cfg.secret)
    $bytes = $h.ComputeHash([Text.Encoding]::UTF8.GetBytes($data))
    return (($bytes | ForEach-Object { $_.ToString("x2") }) -join "")
}
function ConvertTo-B64Url([string]$s) {
    return [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($s)).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}
function ConvertFrom-B64Url([string]$s) {
    $t = $s.Replace('-', '+').Replace('_', '/')
    switch ($t.Length % 4) { 2 { $t += "==" } 3 { $t += "=" } }
    return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($t))
}

function Read-Config {
    if (-not (Test-Path $ConfigFile)) { Write-Host "Not paired yet. Run:  jarvis-listen.ps1 -Pair"; exit 1 }
    $c = Get-Content -Path $ConfigFile -Raw -Encoding UTF8 | ConvertFrom-Json
    if (-not $c.server -or -not $c.reply_topic -or -not $c.cmd_topic -or -not $c.secret -or $c.secret.Length -lt 32) {
        Write-Host "$ConfigFile is incomplete. Pair again:  jarvis-listen.ps1 -Pair"; exit 1
    }
    $c.server = $c.server.TrimEnd('/')
    return $c
}

function Save-Json($obj) {
    $json = $obj | ConvertTo-Json -Depth 4
    [IO.File]::WriteAllText($ConfigFile, $json, (New-Object Text.UTF8Encoding $false))
}

# Signed reply to the phone over the normal PC link topic.
function Send-Reply([string]$re, [bool]$ok, [string]$text, [string]$event = "") {
    if ($text.Length -gt 2500) { $text = $text.Substring(0, 2500) }
    $p = [ordered]@{ re = $re; ok = $ok; text = $text }
    if ($event) { $p.event = $event }
    $b64 = ConvertTo-B64Url ($p | ConvertTo-Json -Compress)
    $ts = Get-Now
    $rb = New-Object byte[] 16
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($rb)
    $nonce = -join ($rb | ForEach-Object { $_.ToString("x2") })
    $mac = Get-Mac ("reply`n$ts`n$nonce`n$b64")
    $body = [Text.Encoding]::UTF8.GetBytes("JARVIS1 $ts $nonce $b64 $mac")
    try {
        Invoke-RestMethod -Method Post -Uri "$($script:Cfg.server)/$($script:Cfg.reply_topic)" -Body $body `
            -Headers @{ Tags = "jarvis-reply"; Priority = "2" } -ContentType "text/plain; charset=utf-8" -TimeoutSec 15 | Out-Null
    } catch { Write-Log "could not send reply: $($_.Exception.Message)" }
}

function Test-Running([string]$name) {
    $n = $name -replace '\.exe$', ''
    return [bool](Get-Process -Name $n -ErrorAction SilentlyContinue)
}

function Get-StatusText([string]$arg) {
    $cpu = (Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average
    $os = Get-CimInstance Win32_OperatingSystem
    $free = [math]::Round($os.FreePhysicalMemory / 1MB, 1); $total = [math]::Round($os.TotalVisibleMemorySize / 1MB, 1)
    $up = (Get-Date) - $os.LastBootUpTime
    $d = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='C:'"
    $disk = "{0} GB free of {1} GB" -f [math]::Round($d.FreeSpace / 1GB), [math]::Round($d.Size / 1GB)
    $top = (Get-Process | Sort-Object -Property WorkingSet64 -Descending | Select-Object -First 5 |
        ForEach-Object { "{0} {1} MB" -f $_.ProcessName, [math]::Round($_.WorkingSet64 / 1MB) }) -join "; "
    $t = "CPU {0}%. Memory: {1} GB free of {2} GB. Disk C: {3}. Up {4} days {5} h {6} min. Most memory: {7}." -f `
        $cpu, $free, $total, $disk, $up.Days, $up.Hours, $up.Minutes, $top
    if ($arg) { if (Test-Running $arg) { $t += " $arg is running." } else { $t += " $arg is NOT running." } }
    return $t
}

function Start-ChildJob([string]$mode, [string]$target) {
    # Long waits run in a separate hidden PowerShell so the listener keeps listening.
    $args2 = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$PSCommandPath`"", "-Child", $mode, "-Target", "`"$target`"")
    Start-Process -FilePath "powershell.exe" -ArgumentList $args2 -WindowStyle Hidden | Out-Null
}

function Invoke-Command2([string]$cmd, [string]$name, [string]$arg, [string]$re) {
    if ($script:Cfg."enable_$cmd" -ne $true) {
        Send-Reply $re $false "`"$cmd`" is switched off on the PC. To allow it, set `"enable_$cmd`": true in $ConfigFile and restart the listener."
        return
    }
    switch ($cmd) {
        "status" { Send-Reply $re $true (Get-StatusText $arg) }
        "lock" { & rundll32.exe 'user32.dll,LockWorkStation'; Send-Reply $re $true "Locked." }
        "sleep" {
            Send-Reply $re $true "Going to sleep now."
            Start-Sleep -Seconds 2
            Add-Type -AssemblyName System.Windows.Forms
            [System.Windows.Forms.Application]::SetSuspendState([System.Windows.Forms.PowerState]::Suspend, $false, $false) | Out-Null
        }
        "shutdown" { & shutdown.exe /s /t 60 /c "Jarvis: shutting down in 60 seconds (run shutdown /a to cancel)"; Send-Reply $re ($LASTEXITCODE -eq 0) "Shutting down in 60 seconds. Say cancel shutdown to stop it." }
        "restart" { & shutdown.exe /r /t 60 /c "Jarvis: restarting in 60 seconds (run shutdown /a to cancel)"; Send-Reply $re ($LASTEXITCODE -eq 0) "Restarting in 60 seconds. Say cancel shutdown to stop it." }
        "cancel_shutdown" { & shutdown.exe /a; if ($LASTEXITCODE -eq 0) { Send-Reply $re $true "Shutdown cancelled." } else { Send-Reply $re $false "There was no shutdown to cancel." } }
        { $_ -eq "watch_exit" -or $_ -eq "shutdown_when_done" } {
            $p = if ($arg) { $arg } else { $name }
            if (-not (Test-Running $p)) { Send-Reply $re $false "$p is not running."; return }
            if ($cmd -eq "watch_exit") { Send-Reply $re $true "Watching $p; I'll call when it exits."; Start-ChildJob "watch" $p }
            else { Send-Reply $re $true "I'll shut down the PC when $p finishes."; Start-ChildJob "shutdownwhendone" $p }
        }
        "run" {
            $line = $null
            if ($name -and $script:Cfg.commands) { $line = $script:Cfg.commands.$name }
            if (-not $line) { Send-Reply $re $false "There is no command called `"$name`" in $ConfigFile."; return }
            Send-Reply $re $true "Started `"$name`"."
            Start-ChildJob "run" $name
        }
        default { Send-Reply $re $false "Unknown command `"$cmd`"." }
    }
}

function Test-Message([string]$msg) {
    $parts = $msg.Trim().Split(' ')
    if ($parts.Count -ne 5 -or $parts[0] -ne "JARVIS1") { Write-Log "rejected: malformed message"; return }
    $ts = 0L
    if (-not [long]::TryParse($parts[1], [ref]$ts)) { Write-Log "rejected: bad timestamp"; return }
    $nonce = $parts[2]; $b64 = $parts[3]; $mac = $parts[4]
    if ($nonce -notmatch '^[0-9a-f]{16,64}$') { Write-Log "rejected: bad nonce"; return }
    if ((Get-Mac ("cmd`n$ts`n$nonce`n$b64")) -ne $mac) { Write-Log "rejected: wrong signature"; return }
    $now = Get-Now
    if ([math]::Abs($now - $ts) -gt $MaxSkew) { Write-Log "rejected: timestamp $([math]::Abs($now - $ts)) s away (check the clocks)"; return }
    $seen = @()
    if (Test-Path $NonceFile) { $seen = @(Get-Content $NonceFile | Where-Object { $_ -match '^(\d+) ' -and [long]$Matches[1] -ge ($now - 600) }) }
    if ($seen | Where-Object { $_.EndsWith(" $nonce") }) { Write-Log "rejected: replayed nonce"; return }
    Set-Content -Path $NonceFile -Value ($seen + "$ts $nonce") -Encoding ASCII
    $p = (ConvertFrom-B64Url $b64) | ConvertFrom-Json
    $cmd = [string]$p.cmd; $name = [string]$p.name; $arg = [string]$p.arg
    foreach ($v in @($cmd, $name, $arg)) { if ($v -notmatch $NameRe) { Write-Log "rejected: bad characters in payload"; return } }
    Write-Log "accepted: $cmd $name $arg"
    try { Invoke-Command2 $cmd $name $arg $nonce } catch { Write-Log "command failed: $($_.Exception.Message)"; Send-Reply $nonce $false "Failed: $($_.Exception.Message)" }
}

# ------------------------------------------------------------------ modes

if ($Pair) {
    if (-not $Code) {
        $ss = Read-Host "Paste the pairing code from the app (it is hidden while you paste), then press Enter" -AsSecureString
        $Code = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($ss))
    }
    $f = $Code.Trim().Split(',')
    if ($f.Count -ne 5 -or $f[0] -ne "J1" -or ($f[2] + $f[3] + $f[4]) -notmatch '^[A-Za-z0-9_-]+$' -or $f[4].Length -lt 32 -or $f[1] -notmatch '^https?://') {
        Write-Host "That does not look like a Jarvis pairing code (it starts with J1,)."; exit 1
    }
    if (Test-Path $ConfigFile) {
        $c = Get-Content -Path $ConfigFile -Raw -Encoding UTF8 | ConvertFrom-Json  # keep the switched-on commands
        $c.server = $f[1]; $c.reply_topic = $f[2]; $c.cmd_topic = $f[3]; $c.secret = $f[4]
    } else {
        $c = [ordered]@{
            server = $f[1]; reply_topic = $f[2]; cmd_topic = $f[3]; secret = $f[4]
            enable_status = $true; enable_lock = $true; enable_watch_exit = $true; enable_cancel_shutdown = $true
            enable_sleep = $false; enable_shutdown = $false; enable_restart = $false; enable_shutdown_when_done = $false
            enable_run = $false
            commands = [ordered]@{ example = "echo hello from Jarvis" }
        }
    }
    Save-Json $c
    Write-Host "Paired. Settings are in $ConfigFile (keep it private)."
    Write-Host "Start it now and at every login with:  powershell -ExecutionPolicy Bypass -File `"$PSCommandPath`" -InstallStartup"
    exit 0
}

$Startup = Join-Path ([Environment]::GetFolderPath("Startup")) "Jarvis listener.lnk"
if ($InstallStartup) {
    $ws = New-Object -ComObject WScript.Shell
    $lnk = $ws.CreateShortcut($Startup)
    $lnk.TargetPath = Join-Path $env:WINDIR "System32\WindowsPowerShell\v1.0\powershell.exe"
    $lnk.Arguments = "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$PSCommandPath`""
    $lnk.WindowStyle = 7
    $lnk.Save()
    Start-Process -FilePath "powershell.exe" -ArgumentList @("-NoProfile", "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass", "-File", "`"$PSCommandPath`"") -WindowStyle Hidden
    Write-Host "Done: the listener runs now (hidden) and starts at every login. Log: $LogFile"
    exit 0
}
if ($UninstallStartup) {
    if (Test-Path $Startup) { Remove-Item $Startup }
    Write-Host "It will no longer start at login. To stop the running one:  jarvis-listen.ps1 -Stop"
    exit 0
}
if ($Stop) {
    $me = $PID
    Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" |
        Where-Object { $_.CommandLine -like "*jarvis-listen.ps1*" -and $_.CommandLine -notlike "*-Child*" -and $_.ProcessId -ne $me } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force; Write-Host "Stopped listener (process $($_.ProcessId))." }
    exit 0
}

$script:Cfg = Read-Config

if ($ShowConfig) {
    Write-Host "Config: $ConfigFile"; Write-Host "Server: $($Cfg.server)"
    $Cfg.PSObject.Properties | Where-Object { $_.Name -like "enable_*" } | ForEach-Object { Write-Host ("{0} = {1}" -f $_.Name, $_.Value) }
    exit 0
}

if ($Child) {
    if ($Target -notmatch $NameRe) { exit 1 }
    if ($Child -eq "run") {
        $line = $Cfg.commands.$Target
        if (-not $line) { exit 1 }
        $proc = Start-Process -FilePath "cmd.exe" -ArgumentList "/c $line" -WindowStyle Hidden -PassThru -Wait
        if ($proc.ExitCode -eq 0) { Send-Reply "" $true "`"$Target`" finished successfully." "exit" }
        else { Send-Reply "" $false "`"$Target`" failed (exit code $($proc.ExitCode))." "exit" }
        exit 0
    }
    while (Test-Running $Target) { Start-Sleep -Seconds 10 }
    if ($Child -eq "shutdownwhendone") {
        & shutdown.exe /s /t 60 /c "Jarvis: $Target finished; shutting down in 60 seconds (shutdown /a cancels)"
        Send-Reply "" ($LASTEXITCODE -eq 0) "$Target has finished. The PC shuts down in 60 seconds." "exit"
    } else {
        Send-Reply "" $true "$Target has finished on your PC." "exit"
    }
    exit 0
}

# Only one listener at a time.
$mutex = New-Object Threading.Mutex($false, "Local\JarvisListener")
if (-not $mutex.WaitOne(0)) { Write-Host "The Jarvis listener is already running."; exit 0 }

Write-Host "Jarvis listener started. Leave this window open (or use -InstallStartup). Log: $LogFile"
Write-Log "listener started"
$since = ""
$backoff = 2
while ($true) {
    $started = Get-Now
    $resp = $null
    try {
        $url = "$($Cfg.server)/$($Cfg.cmd_topic)/json"
        if ($since) { $url += "?since=$since" }
        $req = [Net.HttpWebRequest]::Create($url)
        $req.Timeout = 20000
        $req.ReadWriteTimeout = 120000   # ntfy sends a keepalive well within this
        $resp = $req.GetResponse()
        $reader = New-Object IO.StreamReader($resp.GetResponseStream(), [Text.Encoding]::UTF8)
        while ($null -ne ($line = $reader.ReadLine())) {
            if (-not $line) { continue }
            try { $ev = $line | ConvertFrom-Json } catch { continue }
            if ($ev.event -ne "message") { continue }
            $since = $ev.time
            if ([string]$ev.message -like "JARVIS1 *") {
                try { Test-Message ([string]$ev.message) } catch { Write-Log "error: $($_.Exception.Message)" }
            }
        }
    } catch {
        Write-Log "connection: $($_.Exception.Message)"
    } finally {
        if ($resp) { $resp.Close() }
    }
    if ((Get-Now) - $started -gt 60) { $backoff = 2 } else { $backoff = [math]::Min($backoff * 2, 120) }
    Start-Sleep -Seconds $backoff
}
