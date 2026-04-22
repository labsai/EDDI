$x = [xml](Get-Content 'C:\dev\git\EDDI\target\site\jacoco\jacoco.xml')
$c = $x.report.counter | Where-Object {$_.type -eq 'INSTRUCTION'}
$pct = [math]::Round([int]$c.covered/([int]$c.covered+[int]$c.missed)*100,1)
Write-Host "INSTRUCTION: covered=$($c.covered) missed=$($c.missed) pct=$pct%"

$b = $x.report.counter | Where-Object {$_.type -eq 'BRANCH'}
$bpct = [math]::Round([int]$b.covered/([int]$b.covered+[int]$b.missed)*100,1)
Write-Host "BRANCH: covered=$($b.covered) missed=$($b.missed) pct=$bpct%"

$l = $x.report.counter | Where-Object {$_.type -eq 'LINE'}
$lpct = [math]::Round([int]$l.covered/([int]$l.covered+[int]$l.missed)*100,1)
Write-Host "LINE: covered=$($l.covered) missed=$($l.missed) pct=$lpct%"

Write-Host "`n--- Package Breakdown (top missed instructions) ---"
$x.report.package | ForEach-Object {
    $pkg = $_.name
    $ic = $_.counter | Where-Object {$_.type -eq 'INSTRUCTION'}
    if ($ic) {
        $missed = [int]$ic.missed
        $covered = [int]$ic.covered
        $total = $covered + $missed
        if ($total -gt 0) {
            $p = [math]::Round($covered/$total*100,1)
            [PSCustomObject]@{Package=$pkg; Covered=$covered; Missed=$missed; Pct="$p%"}
        }
    }
} | Sort-Object -Property Missed -Descending | Select-Object -First 15 | Format-Table -AutoSize
