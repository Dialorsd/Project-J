<#
  Jarvis PC link: make your phone ring through ntfy.

  jarvis-ping.ps1 "Message" [-Title "Title"] [-Quiet]   send a ping (-Quiet = notification only, no ringing)
  jarvis-ping.ps1 -SetTopic jarvis-xxxx [-Server URL]    save your topic (copy it from the app)
  jarvis-ping.ps1 -PrintHook                             print the Claude Code hook settings for this PC
  jarvis-ping.ps1 -Hook                                  used by Claude Code hooks (reads hook JSON from stdin)

  The topic is read from the JARVIS_TOPIC environment variable, or from the file .jarvis-ping in your
  home folder. Never commit or share the topic: anyone who knows it can ring your phone.
#>
param(
    [Parameter(Position = 0)][string]$Message = "",
    [string]$Title = "",
    [switch]$Quiet,
    [string]$SetTopic = "",
    [string]$Server = "",
    [switch]$Hook,
    [switch]$PrintHook
)

$ConfigFile = Join-Path $HOME ".jarvis-ping"

function Read-Config {
    $cfg = @{ topic = ""; server = "https://ntfy.sh" }
    if (Test-Path $ConfigFile) {
        foreach ($line in Get-Content $ConfigFile) {
            if ($line -match '^\s*(topic|server)\s*=\s*(.+?)\s*$') { $cfg[$Matches[1]] = $Matches[2] }
        }
    }
    if ($env:JARVIS_TOPIC) { $cfg.topic = $env:JARVIS_TOPIC }
    if ($env:JARVIS_SERVER) { $cfg.server = $env:JARVIS_SERVER }
    return $cfg
}

if ($SetTopic) {
    if ($SetTopic -notmatch '^[A-Za-z0-9_-]{1,64}$') { Write-Host "That does not look like a topic (letters, digits, - and _ only)."; exit 1 }
    $srv = if ($Server) { $Server.TrimEnd('/') } else { "https://ntfy.sh" }
    Set-Content -Path $ConfigFile -Value @("topic=$SetTopic", "server=$srv") -Encoding ASCII
    Write-Host "Saved to $ConfigFile. Test it with:  powershell -ExecutionPolicy Bypass -File `"$PSCommandPath`" `"Hello from my PC`""
    exit 0
}

if ($PrintHook) {
    $p = $PSCommandPath -replace '\\', '/'
    $cmd = '{ "type": "command", "command": "powershell.exe", "args": ["-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "' + $p + '", "-Hook"], "timeout": 30 }'
    Write-Output @"
{
  "hooks": {
    "Stop": [
      { "hooks": [ $cmd ] }
    ],
    "Notification": [
      { "matcher": "permission_prompt|elicitation_dialog|agent_needs_input", "hooks": [ $cmd ] }
    ]
  }
}
"@
    exit 0
}

$cfg = Read-Config
if ($Server) { $cfg.server = $Server }

if ($Hook) {
    # Called by Claude Code: never fail, never block Claude.
    try {
        $raw = [Console]::In.ReadToEnd()
        $j = $raw | ConvertFrom-Json
        $folder = if ($j.cwd) { Split-Path -Leaf $j.cwd } else { "your project" }
        switch ($j.hook_event_name) {
            "Stop" { $Title = "Claude finished"; $Message = "Claude has finished working in $folder." }
            "Notification" {
                $Title = "Claude needs you"
                $m = if ($j.message) { $j.message } else { "Claude is waiting for your input." }
                $Message = "${folder}: $m"
            }
            default { $Title = "Claude"; $Message = "$($j.hook_event_name) in $folder" }
        }
    } catch {
        $Title = "Claude"; $Message = "Claude needs your attention."
    }
}

if (-not $cfg.topic) {
    Write-Host "No topic set. Run:  jarvis-ping.ps1 -SetTopic <topic from the Jarvis app>"
    if ($Hook) { exit 0 } else { exit 1 }
}
if (-not $Message) { $Message = "Ping from your PC" }

$headers = @{}
if ($Title) {
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Title))
    $headers["Title"] = "=?UTF-8?B?$b64?="
}
if ($Quiet) { $headers["Priority"] = "2"; $headers["Tags"] = "quiet" }

try {
    $body = [Text.Encoding]::UTF8.GetBytes($Message)
    $uri = "$($cfg.server.TrimEnd('/'))/$($cfg.topic)"
    Invoke-RestMethod -Method Post -Uri $uri -Body $body -Headers $headers -ContentType "text/plain; charset=utf-8" -TimeoutSec 15 | Out-Null
    if (-not $Hook) { Write-Host "Sent." }
} catch {
    Write-Host "Could not send the ping: $($_.Exception.Message)"
    if (-not $Hook) { exit 1 }
}
exit 0
