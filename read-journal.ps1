$path = 'C:\Users\Administrator.DESKTOP-EGNE9ND\.claude\projects\C--Users-Administrator-DESKTOP-EGNE9ND-AndroidStudioProjects-tfgwj\9e972ab8-0cdf-47b9-ad26-a0c9d8796ebd\subagents\workflows\wf_966a333f-e59\journal.jsonl'
$lines = Get-Content $path
Write-Output "=== JOURNAL entries: $($lines.Count) ==="
$i = 0
foreach ($line in $lines) {
    $e = $line | ConvertFrom-Json
    $type = $e.type
    if ($type -eq 'result') {
        $snippet = $e.result -replace "`n", ' '
        if ($snippet.Length -gt 400) { $snippet = $snippet.Substring(0, 400) }
        Write-Output "--- #$i RESULT: $snippet"
    } elseif ($type -eq 'started') {
        Write-Output "--- #$i STARTED"
    } else {
        Write-Output "--- #$i TYPE=$type"
    }
    $i++
}