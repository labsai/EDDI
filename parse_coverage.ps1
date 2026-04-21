[xml]$xml = Get-Content 'target\site\jacoco\jacoco.xml'
$counters = $xml.report.counter
foreach($c in $counters) {
    $covered = [int]$c.covered
    $missed = [int]$c.missed
    $total = $covered + $missed
    if ($total -gt 0) {
        $pct = [math]::Round(100 * $covered / $total, 1)
    } else {
        $pct = 0
    }
    Write-Output "$($c.type): covered=$covered missed=$missed pct=$pct%"
}

Write-Output ""
Write-Output "--- Target packages ---"
$packages = $xml.report.package
foreach($pkg in $packages) {
    $name = $pkg.name -replace '/', '.'
    if ($name -match 'engine\.internal|backup\.impl|modules\.apicalls\.impl') {
        $instrCounter = $pkg.counter | Where-Object { $_.type -eq 'INSTRUCTION' }
        if ($instrCounter) {
            $cov = [int]$instrCounter.covered
            $mis = [int]$instrCounter.missed
            $tot = $cov + $mis
            if ($tot -gt 0) {
                $pct = [math]::Round(100 * $cov / $tot, 1)
                Write-Output "$name : $pct% ($cov/$tot)"
            }
        }
    }
}
