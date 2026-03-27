<#
.SYNOPSIS
    Build EDDI Manager and deploy to the EDDI backend resource directory.

.DESCRIPTION
    1. Runs `npm run build` to produce the production bundle
    2. Removes old Manager JS/CSS from EDDI (index-*.js, index-*.css only — preserves chat-ui-*)
    3. Copies the new hashed JS/CSS into EDDI's scripts/ directory
    4. Updates manage.html with the new hashed filenames

.PARAMETER EddiPath
    Path to the EDDI repository root. Default: ..\EDDI

.EXAMPLE
    .\deploy.ps1
    .\deploy.ps1 -EddiPath "C:\dev\git\EDDI"
#>

param(
    [string]$EddiPath = (Join-Path $PSScriptRoot "..\EDDI")
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ResourceDir = Join-Path $EddiPath "src\main\resources\META-INF\resources"
$ScriptsJs   = Join-Path $ResourceDir "scripts\js"
$ScriptsCss  = Join-Path $ResourceDir "scripts\css"
$ManageHtml  = Join-Path $ResourceDir "manage.html"

# ─── Validate paths ──────────────────────────────────────────────────────────
if (-not (Test-Path $ManageHtml)) {
    Write-Error "manage.html not found at $ManageHtml. Check -EddiPath parameter."
    exit 1
}

# ─── Step 1: Build ───────────────────────────────────────────────────────────
Write-Host "`n[1/4] Building EDDI Manager..." -ForegroundColor Cyan
npm run build
if ($LASTEXITCODE -ne 0) {
    Write-Error "Build failed!"
    exit 1
}
Write-Host "  Build succeeded." -ForegroundColor Green

# ─── Step 2: Find new assets ────────────────────────────────────────────────
$distAssets = Join-Path $PSScriptRoot "dist\assets"
$newJs  = Get-ChildItem $distAssets -Filter "index-*.js" | Select-Object -First 1
$newCss = Get-ChildItem $distAssets -Filter "index-*.css" | Select-Object -First 1

if (-not $newJs -or -not $newCss) {
    Write-Error "Could not find index-*.js or index-*.css in dist/assets/"
    exit 1
}

Write-Host "`n[2/4] New assets:" -ForegroundColor Cyan
Write-Host "  JS:  $($newJs.Name)"
Write-Host "  CSS: $($newCss.Name)"

# ─── Step 3: Remove old Manager files (preserve chat-ui-*) ─────────────────
Write-Host "`n[3/4] Cleaning old Manager assets..." -ForegroundColor Cyan

$oldJs = Get-ChildItem $ScriptsJs -Filter "index-*.js" -ErrorAction SilentlyContinue
foreach ($f in $oldJs) {
    Write-Host "  Removing $($f.Name)" -ForegroundColor Yellow
    Remove-Item $f.FullName -Force
}

$oldCss = Get-ChildItem $ScriptsCss -Filter "index-*.css" -ErrorAction SilentlyContinue
foreach ($f in $oldCss) {
    Write-Host "  Removing $($f.Name)" -ForegroundColor Yellow
    Remove-Item $f.FullName -Force
}

# ─── Step 4: Copy new assets + update manage.html ──────────────────────────
Write-Host "`n[4/4] Deploying new assets..." -ForegroundColor Cyan

Copy-Item $newJs.FullName  -Destination $ScriptsJs
Copy-Item $newCss.FullName -Destination $ScriptsCss
Write-Host "  Copied $($newJs.Name) -> scripts/js/"
Write-Host "  Copied $($newCss.Name) -> scripts/css/"

# Update manage.html references
$html = Get-Content $ManageHtml -Raw

# Replace the JS reference: src="/scripts/js/index-XXXX.js"
$html = $html -replace 'src="/scripts/js/index-[^"]+\.js"', "src=`"/scripts/js/$($newJs.Name)`""

# Replace the CSS reference: href="/scripts/css/index-XXXX.css"
$html = $html -replace 'href="/scripts/css/index-[^"]+\.css"', "href=`"/scripts/css/$($newCss.Name)`""

Set-Content $ManageHtml -Value $html -NoNewline

Write-Host "`n  Updated manage.html" -ForegroundColor Green
Write-Host "`n[DONE] EDDI Manager deployed successfully!" -ForegroundColor Green
Write-Host "  JS:  /scripts/js/$($newJs.Name)"
Write-Host "  CSS: /scripts/css/$($newCss.Name)`n"

# ─── Step 5 (optional): Commit in EDDI repo ────────────────────────────────
$answer = Read-Host "Commit these assets in the EDDI repo? [y/N]"
if ($answer -match '^[Yy]') {
    Write-Host "`n[5/5] Committing in EDDI repo..." -ForegroundColor Cyan

    # Get the latest Manager commit hash for the message
    $managerHash = git -C $PSScriptRoot log -1 --format="%h" 2>$null
    $managerSubject = git -C $PSScriptRoot log -1 --format="%s" 2>$null
    $commitMsg = "chore: update Manager UI assets"
    if ($managerHash) {
        $commitMsg = "chore: update Manager UI assets (Manager@$managerHash)"
    }


    Push-Location $EddiPath
    try {
        git add "src/main/resources/META-INF/resources/scripts/js/$($newJs.Name)"
        git add "src/main/resources/META-INF/resources/scripts/css/$($newCss.Name)"
        git add "src/main/resources/META-INF/resources/manage.html"

        git commit --no-verify -m $commitMsg
        if ($LASTEXITCODE -eq 0) {
            Write-Host "  Committed: $commitMsg" -ForegroundColor Green
            if ($managerSubject) {
                Write-Host "  Manager:   $managerSubject" -ForegroundColor DarkGray
            }
        } else {
            Write-Host "  Nothing to commit (files unchanged?)" -ForegroundColor Yellow
        }
    } finally {
        Pop-Location
    }
} else {
    Write-Host "Skipped EDDI commit." -ForegroundColor DarkGray
}
