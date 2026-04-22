$x = [xml](Get-Content 'C:\dev\git\EDDI\target\site\jacoco\jacoco.xml')

function Get-ClassCoverage($pkgName) {
    $pkg = $x.report.package | Where-Object {$_.name -eq $pkgName}
    if (-not $pkg) { Write-Host "Package not found: $pkgName"; return }
    
    $pkg.class | ForEach-Object {
        $cls = $_.name.Split('/')[-1]
        $ic = $_.counter | Where-Object {$_.type -eq 'INSTRUCTION'}
        if ($ic) {
            $missed = [int]$ic.missed
            $covered = [int]$ic.covered
            $total = $covered + $missed
            if ($total -gt 0) {
                $p = [math]::Round($covered/$total*100,1)
                [PSCustomObject]@{Class=$cls; Covered=$covered; Missed=$missed; Total=$total; Pct="$p%"}
            }
        }
    } | Sort-Object -Property Missed -Descending | Format-Table -AutoSize
}

Write-Host "=== backup.impl ==="
Get-ClassCoverage 'ai/labs/eddi/backup/impl'

Write-Host "`n=== modules.llm.impl ==="
Get-ClassCoverage 'ai/labs/eddi/modules/llm/impl'

Write-Host "`n=== engine.internal ==="
Get-ClassCoverage 'ai/labs/eddi/engine/internal'
