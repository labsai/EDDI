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
[CmdletBinding(SupportsShouldProcess)]
param(
    [switch]$SkipBuild,
    [switch]$LabelsOnly
)

$ErrorActionPreference = "Stop"
$IMAGE = "eddi-preflight:local"

Write-Information -MessageData "" -InformationAction Continue
Write-Information -MessageData "=== EDDI Preflight Check (Local) ===" -InformationAction Continue
Write-Information -MessageData "" -InformationAction Continue

# ─── Prerequisites ───────────────────────────────────────────────
# Docker Desktop must be running
docker info 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Error -Message "❌ Docker is not running."
    Write-Information -MessageData "   Start Docker Desktop and try again." -InformationAction Continue
    exit 1
}

# JDK required for build (skip check if -SkipBuild)
if (-not $SkipBuild) {
    java -version 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Error -Message "❌ Java (JDK 25) is not installed or not on PATH."
        Write-Information -MessageData "   Install: https://adoptium.net/temurin/releases/?version=25" -InformationAction Continue
        exit 1
    }
}

# ─── Step 1: Build ──────────────────────────────────────────────
if (-not $SkipBuild) {
    Write-Information -MessageData "[1/4] Building application..." -InformationAction Continue
    if ($PSCmdlet.ShouldProcess("Maven", "Build application via mvnw")) {
        & .\mvnw.cmd clean package -DskipTests -Plicense-gen -B
        if ($LASTEXITCODE -ne 0) {
            Write-Error -Message "❌ Maven build failed"
            exit 1
        }
    }

    Write-Information -MessageData "" -InformationAction Continue
    Write-Information -MessageData "[2/4] Building Docker image..." -InformationAction Continue
    if ($PSCmdlet.ShouldProcess("Docker", "Build image $IMAGE")) {
        docker build `
            --build-arg EDDI_VERSION="local" `
            --build-arg EDDI_RELEASE="1" `
            -f src/main/docker/Dockerfile.jvm `
            -t $IMAGE `
            .
        if ($LASTEXITCODE -ne 0) {
            Write-Error -Message "❌ Docker build failed"
            exit 1
        }
    }
} else {
    Write-Information -MessageData "[1/4] Skipping build (using existing image)" -InformationAction Continue
    Write-Information -MessageData "[2/4] Skipping build" -InformationAction Continue

    # Verify image exists
    $null = docker image inspect $IMAGE 2>$null
    if ($LASTEXITCODE -ne 0) {
        Write-Error -Message "❌ Image '$IMAGE' not found. Run without -SkipBuild first."
        exit 1
    }
}

# ─── Step 2: Check Red Hat labels ────────────────────────────────
Write-Information -MessageData "" -InformationAction Continue
Write-Information -MessageData "[3/4] Checking Red Hat labels..." -InformationAction Continue

$labelsJson = docker inspect $IMAGE --format '{{json .Config.Labels}}' 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Error -Message "❌ Failed to inspect image"
    exit 1
}

$labels = $labelsJson | ConvertFrom-Json
$requiredLabels = @("name", "vendor", "version", "release", "summary", "description")
$missing = @()

foreach ($label in $requiredLabels) {
    if ($labels.PSObject.Properties.Name -contains $label) {
        $value = $labels.$label
        Write-Information -MessageData "  ✅ $label = $value" -InformationAction Continue
    } else {
        Write-Warning -Message "  ❌ $label — MISSING"
        $missing += $label
    }
}

if ($missing.Count -gt 0) {
    Write-Information -MessageData "" -InformationAction Continue
    Write-Error -Message "❌ Missing labels: $($missing -join ', ')"
    Write-Information -MessageData "   Fix in: src/main/docker/Dockerfile.jvm (LABEL directive)" -InformationAction Continue
    exit 1
}

# Check /licenses
Write-Information -MessageData "" -InformationAction Continue
Write-Information -MessageData "  Checking /licenses directory..." -InformationAction Continue
if ($PSCmdlet.ShouldProcess("Docker", "Run container to inspect /licenses directory")) {
    docker run --rm --entrypoint "" $IMAGE test -f /licenses/THIRD-PARTY.txt
    if ($LASTEXITCODE -eq 0) {
        Write-Information -MessageData "  ✅ /licenses/THIRD-PARTY.txt present" -InformationAction Continue
    } else {
        Write-Error -Message "  ❌ /licenses/THIRD-PARTY.txt missing — run with -Plicense-gen"
        exit 1
    }
}

if ($LabelsOnly) {
    Write-Information -MessageData "" -InformationAction Continue
    Write-Information -MessageData "✅ Label check passed. Use without -LabelsOnly for full preflight." -InformationAction Continue
    exit 0
}

# ─── Step 3: Run preflight via Docker ────────────────────────────
Write-Information -MessageData "" -InformationAction Continue
Write-Information -MessageData "[4/4] Running preflight check (via Docker)..." -InformationAction Continue
Write-Information -MessageData "  This runs the Red Hat preflight tool inside a container." -InformationAction Continue
Write-Information -MessageData "  Some checks (e.g. HasUniqueTag) will fail — this is expected locally." -InformationAction Continue
Write-Information -MessageData "" -InformationAction Continue

# Run preflight inside a container that has access to the Docker socket
if ($PSCmdlet.ShouldProcess("Docker", "Run preflight check via quay.io/opdev/preflight:stable")) {
    $preflightOutput = @()
    docker run --rm `
        -v //var/run/docker.sock:/var/run/docker.sock `
        --env PFLT_LOGLEVEL=info `
        quay.io/opdev/preflight:stable `
        check container $IMAGE 2>&1 | Tee-Object -Variable preflightOutput

    Write-Information -MessageData "" -InformationAction Continue
    Write-Information -MessageData "=== Results ===" -InformationAction Continue

    $outputStr = $preflightOutput -join "`n"
    if ($outputStr -match "FAILED") {
        Write-Warning -Message "⚠️  Some preflight checks failed (review output above)"
        Write-Information -MessageData "  Note: HasUniqueTag and other registry-dependent checks" -InformationAction Continue
        Write-Information -MessageData "  are expected to fail when checking a local image." -InformationAction Continue
    } else {
        Write-Information -MessageData "✅ All preflight checks passed" -InformationAction Continue
    }
}

Write-Information -MessageData "" -InformationAction Continue
Write-Information -MessageData "Done. For full certification, use the GitHub Actions workflow:" -InformationAction Continue
Write-Information -MessageData "  Actions → 'Red Hat Certification Release' → Run workflow" -InformationAction Continue
