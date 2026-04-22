$xml = [xml](Get-Content 'c:\dev\git\EDDI\target\site\jacoco\jacoco.xml')
$counters = $xml.report.counter
foreach ($c in $counters) {
    $type = $c.type
    $missed = [int]$c.missed
    $covered = [int]$c.covered
    $total = $missed + $covered
    if ($total -gt 0) {
        $pct = [math]::Round(($covered / $total) * 100, 1)
    }
    Write-Host ('{0,-15} {1,6}/{2,6} = {3}%' -f $type, $covered, $total, $pct)
}
