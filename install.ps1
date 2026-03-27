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
    [string]$VaultKey = "",
    [switch]$WithAuth,
    [switch]$WithMonitoring,
    [switch]$Full,
    [switch]$Local,
    [string]$EddiPort = $env:EDDI_PORT,
    [string]$EddiHttpsPort = $env:EDDI_HTTPS_PORT,
    [string]$EddiDir = $env:EDDI_DIR
)

$ErrorActionPreference = "Stop"

# Ensure TLS 1.2 for GitHub downloads (older PS versions default to TLS 1.0)
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
# Suppress Invoke-WebRequest progress bar (drastically speeds up downloads)
$ProgressPreference = 'SilentlyContinue'

# ── Configuration ──────────────────────────────────────────
if (-not $EddiPort)      { $EddiPort = "7070" }
if (-not $EddiHttpsPort) { $EddiHttpsPort = "7443" }
if (-not $EddiDir)       { $EddiDir = Join-Path $HOME ".eddi" }
$EddiBranch = if ($env:EDDI_BRANCH) { $env:EDDI_BRANCH } else { "main" }
$ComposeBaseUrl = "https://raw.githubusercontent.com/labsai/EDDI/$EddiBranch"

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
$EddiVaultMasterKey = ""

# ── Helpers ────────────────────────────────────────────────

function Write-Banner {
    Write-Host ""
    Write-Host "     ______   ____    ____    ____  " -ForegroundColor White
    Write-Host "    / ____/  / __ \  / __ \  /  _/ " -ForegroundColor White
    Write-Host "   / __/    / / / / / / / /  / /   " -ForegroundColor Cyan
    Write-Host "  / /___   / /_/ / / /_/ / _/ /    " -ForegroundColor Cyan
    Write-Host " /_____/  /_____/ /_____/ /___/    " -ForegroundColor Cyan
    Write-Host ""
    Write-Host "   Multi-Agent Orchestration Middleware" -ForegroundColor White
    Write-Host "   https://eddi.labs.ai" -ForegroundColor DarkGray
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

function Test-PortInUse([int]$Port) {
    try {
        $tcp = [System.Net.Sockets.TcpClient]::new()
        $task = $tcp.ConnectAsync("127.0.0.1", $Port)
        $connected = $task.Wait(500)
        $tcp.Dispose()
        return $connected
    } catch {
        return $false
    }
}

function Find-NextFreePort([int]$Start) {
    for ($p = $Start; $p -le $Start + 100; $p++) {
        if (-not (Test-PortInUse $p)) { return $p }
    }
    return 0
}

function Read-Port([string]$PortName, [int]$DefaultPort) {
    $inUse = Test-PortInUse $DefaultPort

    if ($inUse) {
        Write-Warn "Port $DefaultPort is already in use!"
        $nextFree = Find-NextFreePort ($DefaultPort + 1)
        if ($nextFree -gt 0) {
            Write-Host "  Suggested alternative: $nextFree" -ForegroundColor Cyan
        }
    } else {
        Write-Host "  Port $DefaultPort is available." -ForegroundColor DarkGray
    }

    Write-Host ""
    $suggested = if ($inUse -and $nextFree -gt 0) { $nextFree } else { $DefaultPort }

    if ($Defaults) {
        Write-Ok "$PortName port: $suggested"
        return $suggested
    }

    while ($true) {
        $reply = Read-Host "  $PortName port [$suggested]"
        if ([string]::IsNullOrWhiteSpace($reply)) {
            Write-Ok "$PortName port: $suggested"
            return $suggested
        }
        if ($reply -match '^\d+$' -and [int]$reply -ge 1024 -and [int]$reply -le 65535) {
            if (Test-PortInUse ([int]$reply)) {
                Write-Warn "Port $reply is in use. Try another."
            } else {
                Write-Ok "$PortName port: $reply"
                return [int]$reply
            }
        } else {
            Write-Host "  Please enter a valid port (1024-65535)" -ForegroundColor Yellow
        }
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

    # Port check — is EDDI already running?
    try {
        Invoke-RestMethod -Uri "http://localhost:${EddiPort}/q/health/ready" -TimeoutSec 3 -ErrorAction Stop | Out-Null
        $script:EddiAlreadyRunning = $true
        Write-Ok "EDDI already running on port $EddiPort"
    } catch {
        $script:EddiAlreadyRunning = $false
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

$TotalSteps = 5

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

function New-VaultKey {
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [System.Convert]::ToBase64String($bytes)
}

function Step-Security {
    # If a key was provided via CLI parameter, use it
    if ($VaultKey) {
        if ($VaultKey.Length -lt 16) {
            Write-Fail "Vault key must be at least 16 characters (got $($VaultKey.Length))"
        }
        $script:EddiVaultMasterKey = $VaultKey
        return
    }

    # If a key already exists from a previous install, preserve it
    $envFile = Join-Path $EddiDir ".env"
    if (Test-Path $envFile) {
        $existingKey = (Get-Content $envFile | Where-Object { $_ -match '^EDDI_VAULT_MASTER_KEY=(.+)$' } | ForEach-Object { $Matches[1] }) | Select-Object -First 1
        if ($existingKey) {
            $script:EddiVaultMasterKey = $existingKey
            Write-Ok "Vault key preserved from previous install"
            return
        }
    }

    Write-Step 2 $TotalSteps "Security"
    Write-Host "  EDDI encrypts API keys and secrets using a vault master key."
    Write-Host "  This key is unique to your installation — keep it safe!"
    Write-Host ""

    if ($Defaults) {
        # Auto-generate for non-interactive installs
        $script:EddiVaultMasterKey = New-VaultKey
        Write-Ok "Vault master key auto-generated"
        return
    }

    Write-Host "  1) Auto-generate  " -NoNewline; Write-Host "strong random key (recommended)" -ForegroundColor DarkGray
    Write-Host "  2) Custom         " -NoNewline; Write-Host "enter your own passphrase (min 16 chars)" -ForegroundColor DarkGray
    Write-Host ""
    $choice = Read-Choice "1" @("1", "2")

    if ($choice -eq "1") {
        $script:EddiVaultMasterKey = New-VaultKey
        Write-Ok "Vault master key generated"
    } else {
        while ($true) {
            $passphrase = Read-Host "  Enter passphrase" -AsSecureString
            $plaintext = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
                [Runtime.InteropServices.Marshal]::SecureStringToBSTR($passphrase)
            )
            if ($plaintext.Length -lt 16) {
                Write-Host "  Passphrase must be at least 16 characters" -ForegroundColor Yellow
            } else {
                $script:EddiVaultMasterKey = $plaintext
                Write-Ok "Custom passphrase set"
                break
            }
        }
    }
}

function Step-Auth {
    if ($Defaults -or $WithAuth) { return }

    Write-Step 3 $TotalSteps "Authentication"
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

    Write-Step 4 $TotalSteps "Monitoring"
    Write-Host "  1) Skip for now   " -NoNewline; Write-Host "add later with: eddi update" -ForegroundColor DarkGray
    Write-Host "  2) Grafana        " -NoNewline; Write-Host "dashboards + Prometheus metrics" -ForegroundColor DarkGray
    Write-Host ""
    $choice = Read-Choice "1" @("1", "2")
    if ($choice -eq "2") { $script:WithMonitoring = $true }
}

function Step-Ports {
    Write-Step 5 $TotalSteps "Ports"
    Write-Host "  EDDI uses two ports: HTTP for the dashboard/API, HTTPS for secure access."
    Write-Host ""

    $script:EddiPort = Read-Port "HTTP" ([int]$EddiPort)
    $script:EddiHttpsPort = Read-Port "HTTPS" ([int]$EddiHttpsPort)
}

# ── Compose file management ──────────────────────────────

function Get-ComposeFiles {
    New-Item -ItemType Directory -Force -Path $EddiDir | Out-Null

    # Detect script directory (empty when piped via iwr | iex)
    $ScriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { "" }

    $neededFiles = @()

    if ($Db -eq "postgres") {
        $neededFiles += "docker-compose.postgres-only.yml"
    } else {
        $neededFiles += "docker-compose.yml"
    }

    # Local build overlay (overrides image with local build context)
    if ($Local) {
        $repoRoot = if ($ScriptDir) { $ScriptDir } else { Get-Location }
        $localCompose = Join-Path $repoRoot "docker-compose.local.yml"
        $dockerfile = Join-Path $repoRoot "src\main\docker\Dockerfile.jvm"
        if (-not (Test-Path $localCompose)) {
            Write-Fail "-Local requires running from the EDDI repo root.`n     Run: cd C:\path\to\EDDI; .\install.ps1 -Local"
        }
        if (-not (Test-Path $dockerfile)) {
            Write-Fail "Dockerfile not found. Run: .\mvnw.cmd package -DskipTests first."
        }
        $script:ComposeFiles += $localCompose
    }

    if ($WithAuth) {
        $neededFiles += "docker-compose.auth.yml"
    }

    if ($WithMonitoring) {
        $neededFiles += "docker-compose.monitoring.yml"
    }

    # Resolve each file: prefer local copy, fall back to download
    foreach ($f in $neededFiles) {
        $target = Join-Path $EddiDir $f
        $localFile = if ($ScriptDir) { Join-Path $ScriptDir $f } else { "" }

        if ($localFile -and (Test-Path $localFile)) {
            # File exists next to the script — copy to install dir
            Copy-Item $localFile $target -Force
            Write-Host "  Using local $f " -NoNewline; Write-Host "✅" -ForegroundColor Green
        } else {
            # Not available locally — download from GitHub
            $downloadUrl = "$ComposeBaseUrl/$f"
            Write-Host "  Downloading $f... " -NoNewline
            try {
                Invoke-WebRequest -Uri $downloadUrl -OutFile $target -UseBasicParsing -ErrorAction Stop
                Write-Host "✅" -ForegroundColor Green
            } catch {
                Write-Fail "Failed to download $f.`n     URL: $downloadUrl`n     Error: $($_.Exception.Message)`n     Check your internet connection and that the branch '$EddiBranch' exists.";
            }
        }

        $script:ComposeFiles += $target
    }

    # Build compose flags
    $script:ComposeCmdFiles = ($ComposeFiles | ForEach-Object { "-f `"$_`"" }) -join " "

    # Save config for CLI wrapper
    @"
COMPOSE_FILES=$($ComposeFiles -join " ")
EDDI_PORT=$EddiPort
EDDI_HTTPS_PORT=$EddiHttpsPort
EDDI_VAULT_MASTER_KEY=$EddiVaultMasterKey
"@ | Set-Content (Join-Path $EddiDir ".eddi-config")

    # Write .env file for docker compose variable substitution
    $envContent = @"
# EDDI environment - generated by installer
# WARNING: The vault master key encrypts all stored API keys.
#          If you lose this key, encrypted secrets are UNRECOVERABLE.
EDDI_VAULT_MASTER_KEY=$EddiVaultMasterKey
EDDI_PORT=$EddiPort
EDDI_HTTPS_PORT=$EddiHttpsPort
"@
    $envPath = Join-Path $EddiDir ".env"
    $envContent | Set-Content $envPath

    # Restrict .env file permissions (owner-only read/write)
    try {
        $acl = Get-Acl $envPath
        $acl.SetAccessRuleProtection($true, $false)
        $currentUser = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
        $rule = New-Object System.Security.AccessControl.FileSystemAccessRule($currentUser, "FullControl", "Allow")
        $acl.AddAccessRule($rule)
        Set-Acl $envPath $acl
    } catch {
        # Non-fatal — write a warning but continue
        Write-Warn "Could not restrict .env file permissions"
    }
}

# ── Start containers ─────────────────────────────────────

function Start-Eddi {
    Write-Section "Starting EDDI"

    # Export env vars so docker-compose variable substitution picks them up
    $env:EDDI_PORT = $EddiPort
    $env:EDDI_HTTPS_PORT = $EddiHttpsPort
    $env:EDDI_VAULT_MASTER_KEY = $EddiVaultMasterKey

    if ($Local) {
        Write-Host "  Building local Docker image..."
        Write-Host ""
        $envFile = Join-Path $EddiDir ".env"
        $buildArgs = @("compose", "--env-file", $envFile) + ($ComposeFiles | ForEach-Object { @("-f", $_) }) + @("build")
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
        $envFile = Join-Path $EddiDir ".env"
        $pullArgs = @("compose", "--env-file", $envFile) + ($ComposeFiles | ForEach-Object { @("-f", $_) }) + @("pull")
        & docker @pullArgs
        if ($LASTEXITCODE -eq 0) {
            Write-Host ""
            Write-Ok "Images pulled"
        } else {
            Write-Fail "Failed to pull images. Check internet and disk space."
        }
    }

    Write-Host "  Starting containers...   " -NoNewline
    $envFile = Join-Path $EddiDir ".env"
    $upArgs = @("compose", "--env-file", $envFile) + ($ComposeFiles | ForEach-Object { @("-f", $_) }) + @("up", "-d")
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
    Write-Host "  HTTPS      →  " -NoNewline; Write-Host "https://localhost:${EddiHttpsPort}" -ForegroundColor Cyan
    Write-Host "  MCP        →  " -NoNewline; Write-Host "http://localhost:${EddiPort}/mcp" -ForegroundColor Cyan
    Write-Host "  API docs   →  " -NoNewline; Write-Host "http://localhost:${EddiPort}/q/swagger-ui" -ForegroundColor Cyan

    if ($WithAuth) {
        Write-Host "  Keycloak   →  " -NoNewline; Write-Host "http://localhost:8180" -ForegroundColor Cyan -NoNewline
        Write-Host "  (admin/admin)" -ForegroundColor DarkGray
    }

    Write-Host ""
    Write-Host "  ┌─ 🔑 Vault Master Key ──────────────────────────────┐" -ForegroundColor Yellow
    Write-Host "  │                                                    │" -ForegroundColor Yellow
    Write-Host "  │  Stored in: " -ForegroundColor Yellow -NoNewline
    Write-Host "$EddiDir\.env" -NoNewline
    Write-Host "                      │" -ForegroundColor Yellow
    Write-Host "  │  " -ForegroundColor Yellow -NoNewline; Write-Host "Back up this file! If lost, encrypted" -ForegroundColor DarkGray -NoNewline; Write-Host "             │" -ForegroundColor Yellow
    Write-Host "  │  " -ForegroundColor Yellow -NoNewline; Write-Host "secrets (API keys) are unrecoverable." -ForegroundColor DarkGray -NoNewline; Write-Host "             │" -ForegroundColor Yellow
    Write-Host "  └────────────────────────────────────────────────────┘" -ForegroundColor Yellow
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
    Write-Host "  Vault:          " -NoNewline; Write-Host "🔒 enabled" -ForegroundColor White -NoNewline; Write-Host " (unique key)" -ForegroundColor DarkGray
    $authLabel = if ($WithAuth) { "Keycloak" } else { "open access" }
    Write-Host "  Authentication: $authLabel" -ForegroundColor $(if ($WithAuth) { "White" } else { "DarkGray" })
    $monLabel = if ($WithMonitoring) { "Grafana + Prometheus" } else { "none" }
    Write-Host "  Monitoring:     $monLabel" -ForegroundColor $(if ($WithMonitoring) { "White" } else { "DarkGray" })
    Write-Host "  HTTP port:      $EddiPort" -ForegroundColor White
    Write-Host "  HTTPS port:     $EddiHttpsPort" -ForegroundColor White
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
Step-Security
Step-Auth
Step-Monitoring
Step-Ports

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
