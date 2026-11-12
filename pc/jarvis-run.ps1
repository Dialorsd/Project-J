<#
  Runs any command, then makes Jarvis call you with the result and how long it took.
  Example:  jarvis-run.ps1 python train.py --epochs 5
#>
param([Parameter(Mandatory = $true, ValueFromRemainingArguments = $true)][string[]]$Command)

$sw = [Diagnostics.Stopwatch]::StartNew()
$exe = $Command[0]
$rest = @($Command | Select-Object -Skip 1)
$global:LASTEXITCODE = $null
& $exe @rest
$ok = $?
$code = if ($null -ne $LASTEXITCODE) { $LASTEXITCODE } elseif ($ok) { 0 } else { 1 }
$sw.Stop()

$t = $sw.Elapsed
$dur = if ($t.TotalHours -ge 1) { "{0}h {1}m" -f [int][math]::Floor($t.TotalHours), $t.Minutes }
       elseif ($t.TotalMinutes -ge 1) { "{0}m {1}s" -f $t.Minutes, $t.Seconds }
       else { "{0}s" -f $t.Seconds }
$name = Split-Path -Leaf $exe
if ($code -eq 0) {
    $title = "Done: $name"; $msg = "The command '$name' finished successfully after $dur."
} else {
    $title = "Failed: $name"; $msg = "The command '$name' failed with exit code $code after $dur."
}
& (Join-Path $PSScriptRoot "jarvis-ping.ps1") $msg -Title $title
exit $code
