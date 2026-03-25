# ─────────────────────────────────────────────────────────────
#  E.D.D.I — One-Command Install & Onboarding Wizard
#
#  Usage:
#    iwr -useb https://raw.githubusercontent.com/labsai/EDDI/main/install.ps1 | iex
#
#  Options:
#    .\install.ps1 -Defaults                 # all defaults, no prompts
#    .\install.ps1 -Db postgres -WithAuth    # specific choices
# ─────────────────────────────────────────────────────────────

param(
    [switch]$Defaults,
    [ValidateSet("mongodb", "postgres")]
    [string]$Db = "",
    [switch]$WithAuth,
    [switch]$WithMonitoring,
    [switch]$Full,
    [switch]$Local,
    [string]$EddiPort = $env:EDDI_PORT,
    [string]$EddiDir = $env:EDDI_DIR
)

$ErrorActionPreference = "Stop"

# ── Configuration ──────────────────────────────────────────
if (-not $EddiPort) { $EddiPort = "7070" }
if (-not $EddiDir)  { $EddiDir = Join-Path $HOME ".eddi" }
$ComposeBaseUrl = "https://raw.githubusercontent.com/labsai/EDDI/main"

if ($Full) {
    $Db = "postgres"
    $WithAuth = $true
    $WithMonitoring = $true
}

if ($Defaults -and -not $Db) {
    $Db = "mongodb"
}

# ── State ──────────────────────────────────────────────────
$ContainersStarted = $false
$Healthy = $false
$EddiAlreadyRunning = $false
$ComposeFiles = @()
$ComposeCmdFiles = ""

# ── Helpers ────────────────────────────────────────────────

function Write-Banner {
    Write-Host ""
    Write-Host " ╔═══════════════════════════════════════════════════╗" -ForegroundColor Cyan
    Write-Host " ║             E . D . D . I                         ║" -ForegroundColor Cyan
    Write-Host " ║    Multi-Agent Orchestration Middleware           ║" -ForegroundColor Cyan
    Write-Host " ║                                                   ║" -ForegroundColor Cyan
    Write-Host " ║    Setup Wizard                                   ║" -ForegroundColor Cyan
    Write-Host " ╚═══════════════════════════════════════════════════╝" -ForegroundColor Cyan
    Write-Host ""
}

function Write-Ok($msg)   { Write-Host "  ✅ $msg" -ForegroundColor Green }
function Write-Warn($msg) { Write-Host "  ⚠️  $msg" -ForegroundColor Yellow }
function Write-Fail($msg) { Write-Host "  ❌ $msg" -ForegroundColor Red; exit 1 }
function Write-Step($num, $total, $title) {
    Write-Host ""
    Write-Host "─── Step $num of $total`: $title ───────────────────────" -ForegroundColor White
    Write-Host ""
}
function Write-Section($title) {
    Write-Host ""
    Write-Host "─── $title ───────────────────────────────" -ForegroundColor White
    Write-Host ""
}

function Read-Choice($default, [string[]]$valid = @()) {
    if ($Defaults) { return $default }
    while ($true) {
        $reply = Read-Host "  Choose [$default]"
        if ([string]::IsNullOrWhiteSpace($reply)) { return $default }
        if ($valid.Count -eq 0 -or $valid -contains $reply) { return $reply }
        Write-Host "  Please enter one of: $($valid -join ', ')" -ForegroundColor Yellow
    }
}

# ── Pre-flight checks ─────────────────────────────────────

function Test-Prerequisites {
    # Docker
    try {
        $dockerVersion = (docker --version 2>$null) -replace '.*?(\d+\.\d+\.\d+).*', '$1'
        if (-not $dockerVersion) { throw "not found" }
        Write-Ok "Docker found ($dockerVersion)"
    } catch {
        Write-Host "  ❌ Docker is not installed." -ForegroundColor Red
        Write-Host ""

        # Try winget auto-install
        $canWinget = $false
        try { winget --version 2>$null | Out-Null; $canWinget = ($LASTEXITCODE -eq 0) } catch {}

        if ($canWinget -and -not $Defaults) {
            $choice = Read-Host "  Install Docker Desktop now via winget? [Y/n]"
            if (-not $choice -or $choice -match '^[Yy]$') {
                Write-Host "  Installing Docker Desktop..." -ForegroundColor Cyan
                winget install -e --id Docker.DockerDesktop --accept-package-agreements --accept-source-agreements
                Write-Host ""
                Write-Host "  Docker Desktop installed!" -ForegroundColor Green
                Write-Host "  ⚠️  Please start Docker Desktop, wait for it to be ready," -ForegroundColor Yellow
                Write-Host "     then re-run this script." -ForegroundColor Yellow
                exit 0
            }
        }

        Write-Host "     Install Docker Desktop:"
        Write-Host "       winget install Docker.DockerDesktop" -ForegroundColor Cyan
        Write-Host "       — or —"
        Write-Host "       https://docs.docker.com/desktop/install/windows-install/"
        Write-Host ""
        Write-Host "     Prerequisites:"
        Write-Host "       • Windows 10 (build 19041+) or Windows 11"
        Write-Host "       • WSL2 enabled (wsl --install)"
        Write-Host ""
        exit 1
    }

    # Docker daemon
    try {
        docker info 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "not running" }
    } catch {
        Write-Fail "Docker is installed but not running.`n     Start Docker Desktop."
    }

    # Docker Compose
    try {
        $composeVersion = docker compose version --short 2>$null
        if (-not $composeVersion) { throw "not found" }
        Write-Ok "Docker Compose found ($composeVersion)"
    } catch {
        Write-Fail "Docker Compose not found.`n     Install: https://docs.docker.com/compose/install/"
    }

    # Port check
    try {
        Invoke-RestMethod -Uri "http://localhost:${EddiPort}/q/health/ready" -TimeoutSec 3 -ErrorAction Stop | Out-Null
        $script:EddiAlreadyRunning = $true
        Write-Ok "EDDI already running on port $EddiPort"
    } catch {
        try {
            Invoke-WebRequest -Uri "http://localhost:${EddiPort}" -TimeoutSec 2 -ErrorAction Stop | Out-Null
            Write-Fail "Port $EddiPort is in use by another service.`n     Use: `$env:EDDI_PORT='7071'; .\install.ps1"
        } catch {
            $script:EddiAlreadyRunning = $false
        }
    }
}

# ── Detect deployed agents ─────────────────────────────────

function Get-DeployedAgentCount {
    try {
        $response = Invoke-RestMethod -Uri "http://localhost:${EddiPort}/administration/production/deploymentstatus" -TimeoutSec 5 -ErrorAction Stop
        if ($response -is [array]) { return $response.Count }
        return 0
    } catch {
        return 0
    }
}

# ── Wizard steps ──────────────────────────────────────────

$TotalSteps = 3

function Step-Database {
    if ($Db) { return }

    Write-Step 1 $TotalSteps "Database"
    Write-Host "  EDDI needs a database to store agent configs & conversations."
    Write-Host ""
    Write-Host "  1) MongoDB        " -NoNewline; Write-Host "document store, simple setup (default)" -ForegroundColor DarkGray
    Write-Host "  2) PostgreSQL     " -NoNewline; Write-Host "relational, SQL-queryable, familiar" -ForegroundColor DarkGray
    Write-Host ""
    $choice = Read-Choice "1" @("1", "2")
    if ($choice -eq "2") { $script:Db = "postgres" } else { $script:Db = "mongodb" }
}

function Step-Auth {
    if ($Defaults -or $WithAuth) { return }

    Write-Step 2 $TotalSteps "Authentication"
    Write-Host "  How should EDDI handle user access?"
    Write-Host ""
    Write-Host "  1) Open access    " -NoNewline; Write-Host "no login needed (dev / personal)" -ForegroundColor DarkGray
    Write-Host "  2) Keycloak       " -NoNewline; Write-Host "multi-user OIDC (production)" -ForegroundColor DarkGray
    Write-Host ""
    $choice = Read-Choice "1" @("1", "2")
    if ($choice -eq "2") { $script:WithAuth = $true }
}

function Step-Monitoring {
    if ($Defaults -or $WithMonitoring) { return }

    Write-Step 3 $TotalSteps "Monitoring"
    Write-Host "  1) Skip for now   " -NoNewline; Write-Host "add later with: eddi update" -ForegroundColor DarkGray
    Write-Host "  2) Grafana        " -NoNewline; Write-Host "dashboards + Prometheus metrics" -ForegroundColor DarkGray
    Write-Host ""
    $choice = Read-Choice "1" @("1", "2")
    if ($choice -eq "2") { $script:WithMonitoring = $true }
}

# ── Compose file management ──────────────────────────────

function Get-ComposeFiles {
    New-Item -ItemType Directory -Force -Path $EddiDir | Out-Null

    $filesToDownload = @()

    if ($Db -eq "postgres") {
        $filesToDownload += "docker-compose.postgres-only.yml"
    } else {
        $filesToDownload += "docker-compose.yml"
    }

    # Local build overlay (overrides image with local build context)
    if ($Local) {
        # -Local requires running from the EDDI repo checkout
        $repoRoot = if ($PSScriptRoot) { $PSScriptRoot } else { Get-Location }
        $localCompose = Join-Path $repoRoot "docker-compose.local.yml"
        $dockerfile = Join-Path $repoRoot "src\main\docker\Dockerfile.jvm"
        if (-not (Test-Path $localCompose)) {
            Write-Fail "-Local requires running from the EDDI repo root.`n     Run: cd C:\path\to\EDDI; .\install.ps1 -Local"
        }
        if (-not (Test-Path $dockerfile)) {
            Write-Fail "Dockerfile not found. Run: .\mvnw.cmd package -DskipTests first."
        }
        # Use the file directly from the repo (build context must be repo root)
        $script:ComposeFiles += $localCompose
    }

    if ($WithAuth) {
        $filesToDownload += "docker-compose.auth.yml"
    }

    if ($WithMonitoring) {
        $filesToDownload += "docker-compose.monitoring.yml"
    }

    foreach ($f in $filesToDownload) {
        Write-Host "  Downloading $f... " -NoNewline
        try {
            Invoke-WebRequest -Uri "$ComposeBaseUrl/$f" -OutFile (Join-Path $EddiDir $f) -ErrorAction Stop
            Write-Host "✅" -ForegroundColor Green
            $script:ComposeFiles += (Join-Path $EddiDir $f)
        } catch {
            Write-Fail "Failed to download $f.`n     Check your internet connection."
        }
    }

    # Build compose flags
    $script:ComposeCmdFiles = ($ComposeFiles | ForEach-Object { "-f `"$_`"" }) -join " "

    # Save config
    @"
COMPOSE_FILES=$($ComposeFiles -join " ")
EDDI_PORT=$EddiPort
"@ | Set-Content (Join-Path $EddiDir ".eddi-config")
}

# ── Start containers ─────────────────────────────────────

function Start-Eddi {
    Write-Section "Starting EDDI"

    if ($Local) {
        Write-Host "  Building local Docker image..."
        Write-Host ""
        $buildArgs = @("compose") + ($ComposeFiles | ForEach-Object { @("-f", $_) }) + @("build")
        & docker @buildArgs
        if ($LASTEXITCODE -eq 0) {
            Write-Host ""
            Write-Ok "Local image built"
        } else {
            Write-Fail "Failed to build local image.`n     Make sure you ran: .\mvnw.cmd package -DskipTests"
        }
    } else {
        Write-Host "  Pulling images (this may take a minute)..."
        Write-Host ""
        $pullArgs = @("compose") + ($ComposeFiles | ForEach-Object { @("-f", $_) }) + @("pull")
        & docker @pullArgs
        if ($LASTEXITCODE -eq 0) {
            Write-Host ""
            Write-Ok "Images pulled"
        } else {
            Write-Fail "Failed to pull images. Check internet and disk space."
        }
    }

    Write-Host "  Starting containers...   " -NoNewline
    $upArgs = @("compose") + ($ComposeFiles | ForEach-Object { @("-f", $_) }) + @("up", "-d")
    & docker @upArgs 2>$null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "✅" -ForegroundColor Green
        $script:ContainersStarted = $true
    } else {
        Write-Fail "Failed to start containers."
    }
}

# ── Health check ─────────────────────────────────────────

function Wait-ForReady {
    $maxWait = 120
    $elapsed = 0
    Write-Host "  Health check             " -NoNewline

    while ($elapsed -lt $maxWait) {
        try {
            Invoke-RestMethod -Uri "http://localhost:${EddiPort}/q/health/ready" -TimeoutSec 2 -ErrorAction Stop | Out-Null
            Write-Host "✅ ready in ${elapsed}s" -ForegroundColor Green
            $script:Healthy = $true
            return
        } catch {
            Start-Sleep -Seconds 2
            $elapsed += 2
            Write-Host "." -NoNewline
        }
    }

    Write-Host "timeout" -ForegroundColor Red
    Write-Host ""
    Write-Host "  EDDI didn't become ready in ${maxWait}s." -ForegroundColor Yellow
    Write-Host "  Check logs: docker compose $ComposeCmdFiles logs eddi"
    exit 1
}

# ── Import initial agents ──────────────────────────────────

function Import-InitialAgents {
    $agentCount = Get-DeployedAgentCount

    if ($agentCount -eq 0) {
        Write-Host "  Deploying Agent Father...  " -NoNewline
        try {
            Invoke-RestMethod -Uri "http://localhost:${EddiPort}/backup/import/initialAgents" -Method Post -TimeoutSec 60 -ErrorAction Stop | Out-Null
            Write-Host "✅" -ForegroundColor Green
        } catch {
            Write-Host "⚠️  (non-fatal — EDDI is still usable)" -ForegroundColor Yellow
        }
    } else {
        Write-Ok "Found $agentCount deployed agent(s), skipping initial import."
    }
}

# ── Success banner ───────────────────────────────────────

function Write-Success {
    Write-Host ""
    Write-Host "─── 🎉 Setup Complete! ────────────────────────────" -ForegroundColor Green
    Write-Host ""
    Write-Host "  Dashboard  →  " -NoNewline; Write-Host "http://localhost:${EddiPort}" -ForegroundColor Cyan
    Write-Host "  MCP        →  " -NoNewline; Write-Host "http://localhost:${EddiPort}/mcp" -ForegroundColor Cyan
    Write-Host "  API docs   →  " -NoNewline; Write-Host "http://localhost:${EddiPort}/q/swagger-ui" -ForegroundColor Cyan

    if ($WithAuth) {
        Write-Host "  Keycloak   →  " -NoNewline; Write-Host "http://localhost:8180" -ForegroundColor Cyan -NoNewline
        Write-Host "  (admin/admin)" -ForegroundColor DarkGray
    }

    Write-Host ""
    Write-Host "  🤖 Ready to create your first agent?" -ForegroundColor White
    Write-Host "     Open the dashboard and chat with Agent Father!"
    Write-Host "     It will guide you through choosing an AI provider,"
    Write-Host "     setting up API keys, and building your first agent."
    Write-Host ""
    Write-Host "  ┌─ Claude Desktop / Cursor ──────────────────────────┐" -ForegroundColor DarkGray
    Write-Host "  │ Add to your MCP config:                            │" -ForegroundColor DarkGray
    Write-Host "  │   `"eddi`": { `"url`": `"http://localhost:${EddiPort}/mcp`" }   │" -ForegroundColor DarkGray
    Write-Host "  └────────────────────────────────────────────────────┘" -ForegroundColor DarkGray
    Write-Host ""
    Write-Host "  Install dir: $EddiDir" -ForegroundColor DarkGray
    Write-Host ""

    # Open browser
    try { Start-Process "http://localhost:${EddiPort}" } catch {}
}

# ── Config summary ───────────────────────────────────────

function Write-ConfigSummary {
    Write-Section "Configuration"
    $dbLabel = if ($Db -eq "postgres") { "PostgreSQL" } else { "MongoDB" }
    Write-Host "  Database:       $dbLabel" -ForegroundColor White
    $authLabel = if ($WithAuth) { "Keycloak" } else { "open access" }
    Write-Host "  Authentication: $authLabel" -ForegroundColor $(if ($WithAuth) { "White" } else { "DarkGray" })
    $monLabel = if ($WithMonitoring) { "Grafana + Prometheus" } else { "none" }
    Write-Host "  Monitoring:     $monLabel" -ForegroundColor $(if ($WithMonitoring) { "White" } else { "DarkGray" })
    Write-Host "  Port:           $EddiPort" -ForegroundColor White
    Write-Host "  Install dir:    $EddiDir" -ForegroundColor White
}

# ── Main ─────────────────────────────────────────────────

$startTime = Get-Date

Write-Banner
Test-Prerequisites

if ($EddiAlreadyRunning) {
    Write-Section "EDDI Already Running"
    Import-InitialAgents
    Write-Success
    exit 0
}

Step-Database
Step-Auth
Step-Monitoring

Write-ConfigSummary
Get-ComposeFiles

try {
    Start-Eddi
    Wait-ForReady
    Import-InitialAgents
    Write-Success
    $elapsed = [math]::Round(((Get-Date) - $startTime).TotalSeconds)
    Write-Host "  Total setup time: ${elapsed}s" -ForegroundColor DarkGray
    Write-Host ""
} catch {
    if ($ContainersStarted -and -not $Healthy) {
        Write-Host ""
        Write-Host "⚠️  Setup interrupted. Cleaning up containers..." -ForegroundColor Yellow
        $cleanupArgs = @("compose") + ($ComposeFiles | ForEach-Object { @("-f", $_) }) + @("down")
        & docker @cleanupArgs 2>$null
    }
    throw
}
