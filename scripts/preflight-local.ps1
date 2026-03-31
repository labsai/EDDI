<#
.SYNOPSIS
    Run Red Hat container certification preflight checks locally on Windows.

.DESCRIPTION
    Builds the EDDI Docker image and validates it against Red Hat certification
    requirements without needing Linux or WSL. Uses Docker Desktop.

.PARAMETER SkipBuild
    Skip Maven build and Docker image build. Use an existing 'eddi-preflight:local' image.

.PARAMETER LabelsOnly
    Only check Red Hat labels — skip the full preflight tool.

.EXAMPLE
    .\scripts\preflight-local.ps1
    # Full build + label check + preflight

.EXAMPLE
    .\scripts\preflight-local.ps1 -SkipBuild
    # Use existing image, run checks only

.EXAMPLE
    .\scripts\preflight-local.ps1 -LabelsOnly
    # Just verify Red Hat labels are present
#>
param(
    [switch]$SkipBuild,
    [switch]$LabelsOnly
)

$ErrorActionPreference = "Stop"
$IMAGE = "eddi-preflight:local"

Write-Host ""
Write-Host "=== EDDI Preflight Check (Local) ===" -ForegroundColor Cyan
Write-Host ""

# ─── Prerequisites ───────────────────────────────────────────────
# Docker Desktop must be running
$dockerCheck = docker info 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Docker is not running." -ForegroundColor Red
    Write-Host "   Start Docker Desktop and try again." -ForegroundColor DarkGray
    exit 1
}

# JDK required for build (skip check if -SkipBuild)
if (-not $SkipBuild) {
    $javaCheck = java -version 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "❌ Java (JDK 25) is not installed or not on PATH." -ForegroundColor Red
        Write-Host "   Install: https://adoptium.net/temurin/releases/?version=25" -ForegroundColor DarkGray
        exit 1
    }
}

# ─── Step 1: Build ──────────────────────────────────────────────
if (-not $SkipBuild) {
    Write-Host "[1/4] Building application..." -ForegroundColor Yellow
    & .\mvnw.cmd clean package -DskipTests -Plicense-gen -B
    if ($LASTEXITCODE -ne 0) {
        Write-Host "❌ Maven build failed" -ForegroundColor Red
        exit 1
    }

    Write-Host ""
    Write-Host "[2/4] Building Docker image..." -ForegroundColor Yellow
    docker build `
        --build-arg EDDI_VERSION="local" `
        --build-arg EDDI_RELEASE="1" `
        -f src/main/docker/Dockerfile.jvm `
        -t $IMAGE `
        .
    if ($LASTEXITCODE -ne 0) {
        Write-Host "❌ Docker build failed" -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host "[1/4] Skipping build (using existing image)" -ForegroundColor DarkGray
    Write-Host "[2/4] Skipping build" -ForegroundColor DarkGray

    # Verify image exists
    $null = docker image inspect $IMAGE 2>$null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "❌ Image '$IMAGE' not found. Run without -SkipBuild first." -ForegroundColor Red
        exit 1
    }
}

# ─── Step 2: Check Red Hat labels ────────────────────────────────
Write-Host ""
Write-Host "[3/4] Checking Red Hat labels..." -ForegroundColor Yellow

$labelsJson = docker inspect $IMAGE --format '{{json .Config.Labels}}' 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Failed to inspect image" -ForegroundColor Red
    exit 1
}

$labels = $labelsJson | ConvertFrom-Json
$requiredLabels = @("name", "vendor", "version", "release", "summary", "description")
$missing = @()

foreach ($label in $requiredLabels) {
    if ($labels.PSObject.Properties.Name -contains $label) {
        $value = $labels.$label
        Write-Host "  ✅ $label = $value" -ForegroundColor Green
    } else {
        Write-Host "  ❌ $label — MISSING" -ForegroundColor Red
        $missing += $label
    }
}

if ($missing.Count -gt 0) {
    Write-Host ""
    Write-Host "❌ Missing labels: $($missing -join ', ')" -ForegroundColor Red
    Write-Host "   Fix in: src/main/docker/Dockerfile.jvm (LABEL directive)" -ForegroundColor DarkGray
    exit 1
}

# Check /licenses
Write-Host ""
Write-Host "  Checking /licenses directory..." -ForegroundColor DarkGray
docker run --rm --entrypoint "" $IMAGE test -f /licenses/THIRD-PARTY.txt
if ($LASTEXITCODE -eq 0) {
    Write-Host "  ✅ /licenses/THIRD-PARTY.txt present" -ForegroundColor Green
} else {
    Write-Host "  ❌ /licenses/THIRD-PARTY.txt missing — run with -Plicense-gen" -ForegroundColor Red
    exit 1
}

if ($LabelsOnly) {
    Write-Host ""
    Write-Host "✅ Label check passed. Use without -LabelsOnly for full preflight." -ForegroundColor Green
    exit 0
}

# ─── Step 3: Run preflight via Docker ────────────────────────────
Write-Host ""
Write-Host "[4/4] Running preflight check (via Docker)..." -ForegroundColor Yellow
Write-Host "  This runs the Red Hat preflight tool inside a container." -ForegroundColor DarkGray
Write-Host "  Some checks (e.g. HasUniqueTag) will fail — this is expected locally." -ForegroundColor DarkGray
Write-Host ""

# Run preflight inside a container that has access to the Docker socket
docker run --rm `
    -v //var/run/docker.sock:/var/run/docker.sock `
    --env PFLT_LOGLEVEL=info `
    quay.io/opdev/preflight:stable `
    check container $IMAGE 2>&1 | Tee-Object -Variable preflightOutput

Write-Host ""
Write-Host "=== Results ===" -ForegroundColor Cyan

$outputStr = $preflightOutput -join "`n"
if ($outputStr -match "FAILED") {
    Write-Host "⚠️  Some preflight checks failed (review output above)" -ForegroundColor Yellow
    Write-Host "  Note: HasUniqueTag and other registry-dependent checks" -ForegroundColor DarkGray
    Write-Host "  are expected to fail when checking a local image." -ForegroundColor DarkGray
} else {
    Write-Host "✅ All preflight checks passed" -ForegroundColor Green
}

Write-Host ""
Write-Host "Done. For full certification, use the GitHub Actions workflow:" -ForegroundColor DarkGray
Write-Host "  Actions → 'Red Hat Certification Release' → Run workflow" -ForegroundColor DarkGray
