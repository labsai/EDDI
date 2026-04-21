[xml]$xml = Get-Content 'target\site\jacoco\jacoco.xml'
$packages = $xml.report.package
foreach($pkg in $packages) {
    $name = $pkg.name -replace '/', '.'
    if ($name -eq 'ai.labs.eddi.engine.internal') {
        foreach($cls in $pkg.class) {
            $instrCounter = $cls.counter | Where-Object { $_.type -eq 'INSTRUCTION' }
            if ($instrCounter) {
                $cov = [int]$instrCounter.covered
                $mis = [int]$instrCounter.missed
                $tot = $cov + $mis
                if ($tot -gt 50) {
                    $pct = [math]::Round(100 * $cov / $tot, 1)
                    Write-Output "$($cls.name) : $pct% ($cov/$tot, gap=$mis)"
                }
            }
        }
    }
    if ($name -eq 'ai.labs.eddi.backup.impl') {
        Write-Output ""
        Write-Output "--- backup.impl classes (>50 instructions) ---"
        foreach($cls in $pkg.class) {
            $instrCounter = $cls.counter | Where-Object { $_.type -eq 'INSTRUCTION' }
            if ($instrCounter) {
                $cov = [int]$instrCounter.covered
                $mis = [int]$instrCounter.missed
                $tot = $cov + $mis
                if ($tot -gt 50) {
                    $pct = [math]::Round(100 * $cov / $tot, 1)
                    Write-Output "$($cls.name) : $pct% ($cov/$tot, gap=$mis)"
                }
            }
        }
    }
}
