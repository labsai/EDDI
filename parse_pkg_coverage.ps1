param([string]$package)

$xml = [xml](Get-Content 'c:\dev\git\EDDI\target\site\jacoco\jacoco.xml')
$pkgNode = $xml.report.package | Where-Object { $_.name -like "*$package*" }
foreach ($p in $pkgNode) {
    Write-Host "=== $($p.name) ==="
    $classes = $p.class | ForEach-Object {
        $c = $_.counter | Where-Object { $_.type -eq 'INSTRUCTION' }
        [PSCustomObject]@{
            Class   = $_.name.Split('/')[-1]
            Covered = [int]$c.covered
            Missed  = [int]$c.missed
            Total   = [int]$c.covered + [int]$c.missed
            Pct     = if (([int]$c.covered + [int]$c.missed) -gt 0) { 
                [math]::Round([int]$c.covered / ([int]$c.covered + [int]$c.missed) * 100, 1) 
            } else { 0 }
        }
    } | Sort-Object Missed -Descending
    $classes | Format-Table -AutoSize
}
