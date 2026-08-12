<#
.SYNOPSIS
    Build EDDI Manager and deploy to the EDDI backend resource directory.

.DESCRIPTION
    1. Runs `npm run build` to produce the production bundle
    2. Removes hashed assets in EDDI that the new build did not produce.
    3. Copies the entire new assets folder into EDDI's assets/ directory.
    4. Updates manage.html, welcome.html, and workforce.html with the new hashed filenames
    Note: index.html is a smart redirect page and does not reference asset bundles.

    NOTE ON CHUNK COUNT
    The build is route-code-split, so dist/assets holds ~240 JS chunks plus the
    font and Monaco files - around 700 files in total. That is normal and the
    copy has always been wholesale; only ONE index-*.js and ONE index-*.css
    exist, and those are still the only two names patched into the HTML shells.
    Lazy chunks are referenced RELATIVELY ("./dashboard-<hash>.js") from the
    entry chunk, which the backend serves from /assets/, so they resolve under
    /assets/ no matter which SPA path the user is on.

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
$AssetsDir   = Join-Path $ResourceDir "assets"
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
$distFiles = Get-ChildItem $distAssets
$newJs  = $distFiles | Where-Object { $_.Name -like "index-*.js" } | Select-Object -First 1
$newCss = $distFiles | Where-Object { $_.Name -like "index-*.css" } | Select-Object -First 1

if (-not $newJs -or -not $newCss) {
    Write-Error "Could not find index-*.js or index-*.css in dist/assets/"
    exit 1
}

Write-Host "`n[2/4] New main assets:" -ForegroundColor Cyan
Write-Host "  JS:  $($newJs.Name)"
Write-Host "  CSS: $($newCss.Name)"
Write-Host "  Total assets: $($distFiles.Count)" -ForegroundColor DarkGray

# ─── Step 3: Remove old files selectively ────────────────────────────────────
Write-Host "`n[3/4] Cleaning stale Manager assets..." -ForegroundColor Cyan

$removedFiles = @()

# Cleanup legacy locations if any exist (from previous deployment structure)
$oldJs = Get-ChildItem $ScriptsJs -Filter "index-*.js" -ErrorAction SilentlyContinue
foreach ($f in $oldJs) {
    Write-Host "  Removing legacy script $($f.Name)" -ForegroundColor Yellow
    $removedFiles += "src/main/resources/META-INF/resources/scripts/js/$($f.Name)"
    Remove-Item $f.FullName -Force
}

$oldCss = Get-ChildItem $ScriptsCss -Filter "index-*.css" -ErrorAction SilentlyContinue
foreach ($f in $oldCss) {
    Write-Host "  Removing legacy style $($f.Name)" -ForegroundColor Yellow
    $removedFiles += "src/main/resources/META-INF/resources/scripts/css/$($f.Name)"
    Remove-Item $f.FullName -Force
}

# Ensure destination assets dir exists
if (-not (Test-Path $AssetsDir)) {
    New-Item -ItemType Directory -Force -Path $AssetsDir | Out-Null
}

# Clean hashed assets that the new build did not produce.
#
# This is a SET DIFFERENCE against the new dist, not a per-prefix sweep. The
# per-prefix version only deleted an old file when a same-prefixed new one
# existed, so a chunk that vanished between builds — a page renamed, a component
# removed, a lazy boundary moved — was never cleaned and silted up in the EDDI
# repo forever. That was survivable when the build emitted a handful of chunks.
# Route-level code splitting emits ~240, all content-hashed, so a stale set now
# accumulates fast enough to matter.
#
# Only files matching Vite's `name-<8charhash>.ext` shape are considered, so
# anything hand-placed in assets/ is left alone.
# Keep this in step with the same block in deploy-to-local-eddi-repo.sh.
$hashRe = "^(.+)-([A-Za-z0-9_-]{8})\.([A-Za-z0-9]+)$"
$newAssetNames = [System.Collections.Generic.HashSet[string]]::new(
    [string[]]($distFiles | ForEach-Object { $_.Name }),
    [System.StringComparer]::Ordinal
)

foreach ($old in (Get-ChildItem -Path $AssetsDir -File -ErrorAction SilentlyContinue)) {
    if ($old.Name -notmatch $hashRe) { continue }
    if (-not $newAssetNames.Contains($old.Name)) {
        Write-Host "  Removing stale asset $($old.Name)" -ForegroundColor Yellow
        $removedFiles += "src/main/resources/META-INF/resources/assets/$($old.Name)"
        Remove-Item $old.FullName -Force
    }
}

# ─── Step 4: Copy new assets + update manage.html ──────────────────────────
Write-Host "`n[4/4] Deploying new assets..." -ForegroundColor Cyan

Copy-Item "$distAssets\*" -Destination $AssetsDir -Force -Recurse
Write-Host "  Copied all $($distFiles.Count) files into assets/"

# Update manage.html references
$html = Get-Content $ManageHtml -Raw
$html = $html -replace 'src="/(scripts/js|assets)/index-[^"]+\.js"', "src=`"/assets/$($newJs.Name)`""
$html = $html -replace 'href="/(scripts/css|assets)/index-[^"]+\.css"', "href=`"/assets/$($newCss.Name)`""
Set-Content $ManageHtml -Value $html -NoNewline
Write-Host "  Updated manage.html" -ForegroundColor Green

# Update welcome.html references (same bundles, different shell)
$WelcomeHtml = Join-Path $ResourceDir "welcome.html"
if (Test-Path $WelcomeHtml) {
    $wHtml = Get-Content $WelcomeHtml -Raw
    $wHtml = $wHtml -replace 'src="/(scripts/js|assets)/index-[^"]+\.js"', "src=`"/assets/$($newJs.Name)`""
    $wHtml = $wHtml -replace 'href="/(scripts/css|assets)/index-[^"]+\.css"', "href=`"/assets/$($newCss.Name)`""
    Set-Content $WelcomeHtml -Value $wHtml -NoNewline
    Write-Host "  Updated welcome.html" -ForegroundColor Green
}

# Update workforce.html references (same bundles, different shell)
$WorkforceHtml = Join-Path $ResourceDir "workforce.html"
if (Test-Path $WorkforceHtml) {
    $wfHtml = Get-Content $WorkforceHtml -Raw
    $wfHtml = $wfHtml -replace 'src="/(scripts/js|assets)/index-[^"]+\.js"', "src=`"/assets/$($newJs.Name)`""
    $wfHtml = $wfHtml -replace 'href="/(scripts/css|assets)/index-[^"]+\.css"', "href=`"/assets/$($newCss.Name)`""
    Set-Content $WorkforceHtml -Value $wfHtml -NoNewline
    Write-Host "  Updated workforce.html" -ForegroundColor Green
}

Write-Host "`n[DONE] EDDI Manager deployed successfully!" -ForegroundColor Green
Write-Host "  JS:  /assets/$($newJs.Name)"
Write-Host "  CSS: /assets/$($newCss.Name)`n"

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
        # Stage all newly added files from dist/assets into assets/
        foreach ($f in $distFiles) {
             git add "src/main/resources/META-INF/resources/assets/$($f.Name)"
        }
        
        git add "src/main/resources/META-INF/resources/manage.html"
        git add "src/main/resources/META-INF/resources/welcome.html" 2>$null
        git add "src/main/resources/META-INF/resources/workforce.html" 2>$null
        git add "src/main/resources/META-INF/resources/index.html" 2>$null
        
        # Stage the specific old files that were deleted
        foreach ($removed in $removedFiles) {
            git add $removed
        }

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
