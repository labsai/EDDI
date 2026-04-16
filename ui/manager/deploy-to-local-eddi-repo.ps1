<#
.SYNOPSIS
    Build EDDI Manager and deploy to the EDDI backend resource directory.

.DESCRIPTION
    1. Runs `npm run build` to produce the production bundle
    2. Cleans up old hashed assets from previous builds.
    3. Copies the entire new assets folder into EDDI's assets/ directory.
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
Write-Host "`n[3/4] Cleaning old Manager assets (selectively)..." -ForegroundColor Cyan

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

# Clean old versions of the currently generated files in assets/
# Match files with 8-character hashes: [prefix]-[hash].[ext]
foreach ($f in $distFiles) {
    if ($f.Name -match "^(.+)-([A-Za-z0-9_-]{8})\.([A-Za-z0-9]+)$") {
        $prefix = $matches[1]
        $ext = $matches[3]
        $oldMatches = Get-ChildItem -Path $AssetsDir -Filter "$prefix-*.$ext" -ErrorAction SilentlyContinue
        foreach ($old in $oldMatches) {
            # ensure it has an 8-character hash to avoid accidentally removing e.g. index-xyz-abc.js
            if ($old.Name -match "^(.+)-([A-Za-z0-9_-]{8})\.([A-Za-z0-9]+)$") {
                if ($old.Name -ne $f.Name) {
                    Write-Host "  Removing old asset $($old.Name)" -ForegroundColor Yellow
                    $removedFiles += "src/main/resources/META-INF/resources/assets/$($old.Name)"
                    Remove-Item $old.FullName -Force
                }
            }
        }
    }
}

# ─── Step 4: Copy new assets + update manage.html ──────────────────────────
Write-Host "`n[4/4] Deploying new assets..." -ForegroundColor Cyan

Copy-Item "$distAssets\*" -Destination $AssetsDir -Force -Recurse
Write-Host "  Copied all $($distFiles.Count) files into assets/"

# Update manage.html references
$html = Get-Content $ManageHtml -Raw

# Replace the HTML references to either /scripts/js or /assets/ logic
$html = $html -replace 'src="/(scripts/js|assets)/index-[^"]+\.js"', "src=`"/assets/$($newJs.Name)`""
$html = $html -replace 'href="/(scripts/css|assets)/index-[^"]+\.css"', "href=`"/assets/$($newCss.Name)`""

Set-Content $ManageHtml -Value $html -NoNewline

Write-Host "`n  Updated manage.html" -ForegroundColor Green
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
