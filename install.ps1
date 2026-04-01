<#
.SYNOPSIS
    E.D.D.I — One-Command Install & Onboarding Wizard

.DESCRIPTION
    Installs and configures E.D.D.I (Enhanced Dialog Driven Interface),
    a multi-agent orchestration middleware. Downloads Docker Compose files,
    configures database, authentication, monitoring, and starts the platform.

.PARAMETER Defaults
    Accept all defaults without prompting (non-interactive mode).

.PARAMETER Database
    Database engine: 'mongodb' (default) or 'postgres'.

.PARAMETER VaultKey
    Custom vault master key for encrypting API keys (min 16 characters).

.PARAMETER WithAuth
    Include Keycloak authentication (multi-user OIDC).

.PARAMETER WithMonitoring
    Include Grafana + Prometheus monitoring stack.

.PARAMETER Full
    Enable all options: PostgreSQL, Keycloak, Monitoring.

.PARAMETER Local
    Use locally built Docker image instead of pulling from Docker Hub.

.PARAMETER EddiPort
    HTTP port for EDDI (default: 7070, or EDDI_PORT env var).

.PARAMETER EddiHttpsPort
    HTTPS port for EDDI (default: 7443, or EDDI_HTTPS_PORT env var).

.PARAMETER EddiDir
    Installation directory (default: ~/.eddi, or EDDI_DIR env var).

.EXAMPLE
    .\install.ps1
    Interactive wizard with all prompts.

.EXAMPLE
    .\install.ps1 -Defaults
    Install with all default settings, no prompts.

.EXAMPLE
    .\install.ps1 -Database postgres -WithAuth
    Install with PostgreSQL and Keycloak authentication.

.EXAMPLE
    iwr -useb https://raw.githubusercontent.com/labsai/EDDI/main/install.ps1 | iex
    One-line remote install (uses defaults).

.LINK
    https://eddi.labs.ai
#>

[CmdletBinding(SupportsShouldProcess)]
param(
    [switch]$Defaults,
    [ValidateSet("mongodb", "postgres")]
    [string]$Database = "",
    [string]$VaultKey = "",
    [switch]$WithAuth,
    [switch]$WithMonitoring,
    [switch]$Full,
    [switch]$Local,
    [switch]$Help,
    [string]$EddiPort = $env:EDDI_PORT,
    [string]$EddiHttpsPort = $env:EDDI_HTTPS_PORT,
    [string]$EddiDir = $env:EDDI_DIR
)

$ErrorActionPreference = "Stop"
$InformationPreference = "Continue"

# Show help and exit if -Help flag is passed
if ($Help) {
    Get-Help $MyInvocation.MyCommand.Path -Detailed
    exit 0
}

# Detect piped execution (iwr | iex) — disable interactive prompts
if (-not [Environment]::UserInteractive -or $Host.Name -eq 'Default Host') {
    $Defaults = $true
}

# Ensure TLS 1.2 for GitHub downloads (older PS versions default to TLS 1.0)
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
# Suppress Invoke-WebRequest progress bar (drastically speeds up downloads)
$ProgressPreference = 'SilentlyContinue'

# ── Configuration ──────────────────────────────────────────
if (-not $EddiPort) { $EddiPort = "7070" }
if (-not $EddiHttpsPort) { $EddiHttpsPort = "7443" }
if (-not $EddiDir) { $EddiDir = Join-Path -Path $HOME -ChildPath ".eddi" }
$EddiBranch = if ($env:EDDI_BRANCH) { $env:EDDI_BRANCH } else { "main" }
$ComposeBaseUrl = "https://raw.githubusercontent.com/labsai/EDDI/$EddiBranch"

if ($Full) {
    $Database = "postgres"
    $WithAuth = $true
    $WithMonitoring = $true
}

if ($Defaults -and -not $Database) {
    $Database = "mongodb"
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
    Write-Information -MessageData ""
    Write-Information -MessageData "     ______   ____    ____    ____ "
    Write-Information -MessageData "    / ____/  / __ \  / __ \  /  _/ "
    Write-Information -MessageData "   / __/    / / / / / / / /  / /   "
    Write-Information -MessageData "  / /___   / /_/ / / /_/ / _/ /    "
    Write-Information -MessageData " /_____/  /_____/ /_____/ /___/    "
    Write-Information -MessageData ""
    Write-Information -MessageData "   Multi-Agent Orchestration Middleware"
    Write-Information -MessageData "   https://eddi.labs.ai"
    Write-Information -MessageData ""
}

function Write-Ok($msg) { Write-Information -MessageData "  ✅ $msg" }
function Write-Warn($msg) { Write-Warning -Message "  ⚠️  $msg" }
function Write-Fail($msg) {
    Write-Information -MessageData "  ❌ $msg"
    throw $msg
}
function Write-Step($num, $total, $title) {
    Write-Information -MessageData ""
    Write-Information -MessageData "─── Step $num of $total`: $title ───────────────────────"
    Write-Information -MessageData ""
}
function Write-Section($title) {
    Write-Information -MessageData ""
    Write-Information -MessageData "─── $title ───────────────────────────────"
    Write-Information -MessageData ""
}

function Read-Choice($default, [string[]]$valid = @()) {
    if ($Defaults) { return $default }
    while ($true) {
        $reply = Read-Host "  Choose [$default]"
        if ([string]::IsNullOrWhiteSpace($reply)) { return $default }
        if ($valid.Count -eq 0 -or $valid -contains $reply) { return $reply }
        Write-Information -MessageData "  Please enter one of: $($valid -join ', ')"
    }
}

function Test-PortInUse([int]$Port) {
    $tcp = $null
    try {
        $tcp = [System.Net.Sockets.TcpClient]::new()
        $task = $tcp.ConnectAsync("127.0.0.1", $Port)
        $task.Wait(500) | Out-Null
        return $tcp.Connected
    }
    catch {
        return $false
    }
    finally {
        if ($tcp) { $tcp.Dispose() }
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
            Write-Information -MessageData "  Suggested alternative: $nextFree"
        }
    }
    else {
        Write-Information -MessageData "  Port $DefaultPort is available."
    }

    Write-Information -MessageData ""
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
            }
            else {
                Write-Ok "$PortName port: $reply"
                return [int]$reply
            }
        }
        else {
            Write-Information -MessageData "  Please enter a valid port (1024-65535)"
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
    }
    catch {
        Write-Information -MessageData "  ❌ Docker is not installed."
        Write-Information -MessageData ""

        # Try winget auto-install
        $canWinget = $false
        try { winget --version 2>$null | Out-Null; $canWinget = ($LASTEXITCODE -eq 0) } catch { Write-Verbose $_.Exception.Message }

        if ($canWinget -and -not $Defaults) {
            $choice = Read-Host "  Install Docker Desktop now via winget? [Y/n]"
            if (-not $choice -or $choice -match '^[Yy]$') {
                Write-Information -MessageData "  Installing Docker Desktop..."
                winget install -e --id Docker.DockerDesktop --accept-package-agreements --accept-source-agreements
                Write-Information -MessageData ""
                Write-Information -MessageData "  Docker Desktop installed!"
                Write-Information -MessageData "  ⚠️  Please start Docker Desktop, wait for it to be ready,"
                Write-Information -MessageData "     then re-run this script."
                exit 0
            }
        }

        Write-Information -MessageData "     Install Docker Desktop:"
        Write-Information -MessageData "       winget install Docker.DockerDesktop"
        Write-Information -MessageData "       — or —"
        Write-Information -MessageData "       https://docs.docker.com/desktop/install/windows-install/"
        Write-Information -MessageData ""
        Write-Information -MessageData "     Prerequisites:"
        Write-Information -MessageData "       • Windows 10 (build 19041+) or Windows 11"
        Write-Information -MessageData "       • WSL2 enabled (wsl --install)"
        Write-Information -MessageData ""
        exit 1
    }

    # Docker daemon
    try {
        docker info 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "not running" }
    }
    catch {
        Write-Fail "Docker is installed but not running.`n     Start Docker Desktop."
    }

    # Docker Compose
    try {
        $composeVersion = docker compose version --short 2>$null
        if (-not $composeVersion) { throw "not found" }
        Write-Ok "Docker Compose found ($composeVersion)"
    }
    catch {
        Write-Fail "Docker Compose not found.`n     Install: https://docs.docker.com/compose/install/"
    }

    # Disk space check (need ~2GB for images)
    try {
        $driveInfo = [System.IO.DriveInfo]::new($HOME.Substring(0, 1))
        $freeGB = [math]::Round($driveInfo.AvailableFreeSpace / 1GB)
        if ($freeGB -lt 3) {
            Write-Warn "Low disk space (${freeGB}GB free). EDDI images need ~2GB."
            Write-Warn "Free space: docker system prune"
        }
    }
    catch {
        Write-Verbose "Could not check disk space: $($_.Exception.Message)"
    }

    # Port check — is EDDI already running?
    try {
        Invoke-RestMethod -Uri "http://localhost:${EddiPort}/q/health/ready" -TimeoutSec 3 -ErrorAction Stop | Out-Null
        $script:EddiAlreadyRunning = $true
        Write-Ok "EDDI already running on port $EddiPort"
    }
    catch {
        $script:EddiAlreadyRunning = $false
    }
}

# ── Detect deployed agents ─────────────────────────────────

function Get-DeployedAgentCount {
    try {
        $response = Invoke-RestMethod -Uri "http://localhost:${EddiPort}/administration/production/deploymentstatus" -TimeoutSec 5 -ErrorAction Stop
        if ($response -is [array]) { return $response.Count }
        return 0
    }
    catch {
        return 0
    }
}

# ── Wizard steps ──────────────────────────────────────────

$TotalSteps = 5

function Step-Database {
    if ($Database) { return }

    Write-Step -num 1 -total $TotalSteps -title "Database"
    Write-Information -MessageData "  EDDI needs a database to store agent configs & conversations."
    Write-Information -MessageData ""
    Write-Information -MessageData "  1) MongoDB        document store, simple setup (default)"
    Write-Information -MessageData "  2) PostgreSQL     relational, SQL-queryable, familiar"
    Write-Information -MessageData ""
    $choice = Read-Choice "1" @("1", "2")
    if ($choice -eq "2") { $script:Database = "postgres" } else { $script:Database = "mongodb" }
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
    $envFile = Join-Path -Path $EddiDir -ChildPath ".env"
    if (Test-Path $envFile) {
        $existingKey = (Get-Content $envFile | Where-Object { $_ -match '^EDDI_VAULT_MASTER_KEY=(.+)$' } | ForEach-Object { $Matches[1] }) | Select-Object -First 1
        if ($existingKey) {
            $script:EddiVaultMasterKey = $existingKey
            Write-Ok "Vault key preserved from previous install"
            return
        }
    }

    Write-Step -num 2 -total $TotalSteps -title "Security"
    Write-Information -MessageData "  EDDI encrypts API keys and secrets using a vault master key."
    Write-Information -MessageData "  This key is unique to your installation — keep it safe!"
    Write-Information -MessageData ""

    if ($Defaults) {
        # Auto-generate for non-interactive installs
        $script:EddiVaultMasterKey = New-VaultKey
        Write-Ok "Vault master key auto-generated"
        return
    }

    Write-Information -MessageData "  1) Auto-generate  strong random key (recommended)"
    Write-Information -MessageData "  2) Custom         enter your own passphrase (min 16 chars)"
    Write-Information -MessageData ""
    $choice = Read-Choice "1" @("1", "2")

    if ($choice -eq "1") {
        $script:EddiVaultMasterKey = New-VaultKey
        Write-Ok "Vault master key generated"
    }
    else {
        while ($true) {
            $passphrase = Read-Host -Prompt "  Enter passphrase" -AsSecureString
            $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($passphrase)
            try {
                $plaintext = [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
            }
            finally {
                [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
            }
            if ($plaintext.Length -lt 16) {
                Write-Information -MessageData "  Passphrase must be at least 16 characters"
            }
            else {
                $script:EddiVaultMasterKey = $plaintext
                Write-Ok "Custom passphrase set"
                break
            }
        }
    }
}

function Step-Auth {
    if ($Defaults -or $WithAuth) { return }

    Write-Step -num 3 -total $TotalSteps -title "Authentication"
    Write-Information -MessageData "  How should EDDI handle user access?"
    Write-Information -MessageData ""
    Write-Information -MessageData "  1) Open access    no login needed (dev / personal)"
    Write-Information -MessageData "  2) Keycloak       multi-user OIDC (production)"
    Write-Information -MessageData ""
    $choice = Read-Choice "1" @("1", "2")
    if ($choice -eq "2") { $script:WithAuth = $true }
}

function Step-Monitoring {
    if ($Defaults -or $WithMonitoring) { return }

    Write-Step -num 4 -total $TotalSteps -title "Monitoring"
    Write-Information -MessageData "  1) Skip for now   add later with: eddi update"
    Write-Information -MessageData "  2) Grafana        dashboards + Prometheus metrics"
    Write-Information -MessageData ""
    $choice = Read-Choice "1" @("1", "2")
    if ($choice -eq "2") { $script:WithMonitoring = $true }
}

function Step-Ports {
    Write-Step -num 5 -total $TotalSteps -title "Ports"
    Write-Information -MessageData "  EDDI uses two ports: HTTP for the dashboard/API, HTTPS for secure access."
    Write-Information -MessageData ""

    $script:EddiPort = Read-Port "HTTP" ([int]$EddiPort)
    $script:EddiHttpsPort = Read-Port "HTTPS" ([int]$EddiHttpsPort)
}

# ── Compose file management ──────────────────────────────

function Get-ComposeFiles {
    New-Item -ItemType Directory -Force -Path $EddiDir | Out-Null

    # Detect script directory (empty when piped via iwr | iex)
    $ScriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { "" }

    $neededFiles = @()

    if ($Database -eq "postgres") {
        $neededFiles += "docker-compose.postgres-only.yml"
    }
    else {
        $neededFiles += "docker-compose.yml"
    }

    # Local build overlay (overrides image with local build context)
    if ($Local) {
        $repoRoot = if ($ScriptDir) { $ScriptDir } else { Get-Location }
        $localCompose = Join-Path -Path $repoRoot -ChildPath "docker-compose.local.yml"
        $dockerfile = Join-Path -Path $repoRoot -ChildPath "src\main\docker\Dockerfile.jvm"
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
        $target = Join-Path -Path $EddiDir -ChildPath $f
        $localFile = if ($ScriptDir) { Join-Path -Path $ScriptDir -ChildPath $f } else { "" }

        if ($localFile -and (Test-Path $localFile)) {
            # File exists next to the script — copy to install dir
            Copy-Item -Path $localFile -Destination $target -Force
            Write-Information -MessageData "  Using local $f ✅"
        }
        else {
            # Not available locally — download from GitHub
            $downloadUrl = "$ComposeBaseUrl/$f"
            Write-Information -MessageData "  Downloading $f... "
            try {
                Invoke-WebRequest -Uri $downloadUrl -OutFile $target -UseBasicParsing -ErrorAction Stop
                Write-Information -MessageData "✅"
            }
            catch {
                Write-Fail "Failed to download $f.`n     URL: $downloadUrl`n     Error: $($_.Exception.Message)`n     Check your internet connection and that the branch '$EddiBranch' exists.";
            }
        }

        $script:ComposeFiles += $target
    }

    # Download monitoring support files if needed
    if ($WithMonitoring) {
        Write-Information -MessageData ""
        Write-Information -MessageData "  Downloading monitoring configuration..."
        $monitoringFiles = @(
            "prometheus.yml",
            "grafana-data/provisioning/dashboards/dashboard.yml",
            "grafana-data/provisioning/datasources/prometheus.yml",
            "grafana-data/dashboards/eddi-operations.json"
        )
        foreach ($mf in $monitoringFiles) {
            $mfTarget = Join-Path -Path $EddiDir -ChildPath $mf
            $mfLocalFile = if ($ScriptDir) { Join-Path -Path $ScriptDir -ChildPath $mf } else { "" }
            $mfDir = Split-Path -Path $mfTarget -Parent
            if (-not (Test-Path $mfDir)) { New-Item -ItemType Directory -Force -Path $mfDir | Out-Null }

            if ($mfLocalFile -and (Test-Path $mfLocalFile)) {
                Copy-Item -Path $mfLocalFile -Destination $mfTarget -Force
            }
            else {
                $mfUrl = "$ComposeBaseUrl/$mf"
                Write-Information -MessageData "  Downloading $mf... "
                try {
                    Invoke-WebRequest -Uri $mfUrl -OutFile $mfTarget -UseBasicParsing -ErrorAction Stop
                    Write-Information -MessageData "✅"
                }
                catch {
                    Write-Warn "Failed to download $mf (monitoring may not work)"
                }
            }
        }
    }

    # Build compose flags
    $script:ComposeCmdFiles = ($ComposeFiles | ForEach-Object { "-f `"$_`"" }) -join " "

    # Save config for CLI wrapper (vault key NOT stored here — it's in .env only)
    $configPath = Join-Path -Path $EddiDir -ChildPath ".eddi-config"
    @"
COMPOSE_FILES=$($ComposeFiles -join " ")
EDDI_PORT=$EddiPort
EDDI_HTTPS_PORT=$EddiHttpsPort
"@ | Set-Content -Path $configPath

    # Write .env file for docker compose variable substitution
    # Escape double quotes in vault key to prevent .env corruption
    $escapedKey = $EddiVaultMasterKey -replace '"', '\"'
    $envContent = @"
# EDDI environment - generated by installer
# WARNING: The vault master key encrypts all stored API keys.
#          If you lose this key, encrypted secrets are UNRECOVERABLE.
EDDI_VAULT_MASTER_KEY="$escapedKey"
EDDI_PORT=$EddiPort
EDDI_HTTPS_PORT=$EddiHttpsPort
"@
    $envPath = Join-Path -Path $EddiDir -ChildPath ".env"
    $envContent | Set-Content -Path $envPath

    # Restrict sensitive file permissions (owner-only read/write)
    foreach ($securePath in @($envPath, $configPath)) {
        try {
            $acl = Get-Acl $securePath
            $acl.SetAccessRuleProtection($true, $false)
            $currentUser = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
            $rule = New-Object System.Security.AccessControl.FileSystemAccessRule($currentUser, "FullControl", "Allow")
            $acl.AddAccessRule($rule)
            Set-Acl $securePath $acl
        }
        catch {
            Write-Warn "Could not restrict permissions on $(Split-Path $securePath -Leaf)"
        }
    }
}

# ── Start containers ─────────────────────────────────────

function Start-Eddi {
    [CmdletBinding(SupportsShouldProcess)]
    param()
    if (-not $PSCmdlet.ShouldProcess("EDDI", "Start Containers")) { return }
    Write-Section "Starting EDDI"

    # Export env vars so docker-compose variable substitution picks them up
    $env:EDDI_PORT = $EddiPort
    $env:EDDI_HTTPS_PORT = $EddiHttpsPort
    $env:EDDI_VAULT_MASTER_KEY = $EddiVaultMasterKey

    if ($Local) {
        Write-Information -MessageData "  Building local Docker image..."
        Write-Information -MessageData ""
        $envFile = Join-Path -Path $EddiDir -ChildPath ".env"
        $buildArgs = @("compose", "--env-file", $envFile) + ($ComposeFiles | ForEach-Object { @("-f", $_) }) + @("build")
        & docker @buildArgs
        if ($LASTEXITCODE -eq 0) {
            Write-Information -MessageData ""
            Write-Ok "Local image built"
        }
        else {
            Write-Fail "Failed to build local image.`n     Make sure you ran: .\mvnw.cmd package -DskipTests"
        }
    }
    else {
        Write-Information -MessageData "  Pulling images (this may take a minute)..."
        Write-Information -MessageData ""
        $envFile = Join-Path -Path $EddiDir -ChildPath ".env"
        $pullArgs = @("compose", "--env-file", $envFile) + ($ComposeFiles | ForEach-Object { @("-f", $_) }) + @("pull")
        & docker @pullArgs
        if ($LASTEXITCODE -eq 0) {
            Write-Information -MessageData ""
            Write-Ok "Images pulled"
        }
        else {
            Write-Fail "Failed to pull images. Check internet and disk space."
        }
    }

    Write-Information -MessageData "  Starting containers...   "
    $envFile = Join-Path -Path $EddiDir -ChildPath ".env"
    $upArgs = @("compose", "--env-file", $envFile) + ($ComposeFiles | ForEach-Object { @("-f", $_) }) + @("up", "-d")
    & docker @upArgs
    if ($LASTEXITCODE -eq 0) {
        Write-Information -MessageData "✅"
        $script:ContainersStarted = $true
    }
    else {
        Write-Fail "Failed to start containers."
    }
}

# ── Health check ─────────────────────────────────────────

function Wait-ForReady {
    $maxWait = 120
    $elapsed = 0
    Write-Information -MessageData "  Health check             "

    while ($elapsed -lt $maxWait) {
        try {
            Invoke-RestMethod -Uri "http://localhost:${EddiPort}/q/health/ready" -TimeoutSec 2 -ErrorAction Stop | Out-Null
            Write-Information -MessageData "✅ ready in ${elapsed}s"
            $script:Healthy = $true
            return
        }
        catch {
            Start-Sleep -Seconds 2
            $elapsed += 2
            Write-Information -MessageData "."
        }
    }

    Write-Information -MessageData "timeout"
    Write-Information -MessageData ""
    Write-Information -MessageData "  EDDI didn't become ready in ${maxWait}s."
    Write-Information -MessageData "  Check logs: eddi logs eddi"
    Write-Fail "Health check timed out after ${maxWait}s"
}

# ── Import initial agents ──────────────────────────────────

function Import-InitialAgents {
    $agentCount = Get-DeployedAgentCount

    if ($agentCount -eq 0) {
        Write-Information -MessageData "  Deploying Agent Father...  "
        try {
            Invoke-RestMethod -Uri "http://localhost:${EddiPort}/backup/import/initialAgents" -Method Post -TimeoutSec 60 -ErrorAction Stop | Out-Null
            Write-Information -MessageData "✅"
        }
        catch {
            Write-Information -MessageData "⚠️  (non-fatal — EDDI is still usable)"
        }
    }
    else {
        Write-Ok "Found $agentCount deployed agent(s), skipping initial import."
    }
}

# ── Success banner ───────────────────────────────────────

function Write-Success {
    Write-Information -MessageData ""
    Write-Information -MessageData "─── 🎉 Setup Complete! ────────────────────────────"
    Write-Information -MessageData ""
    Write-Information -MessageData "  Dashboard  →  http://localhost:${EddiPort}"
    Write-Information -MessageData "  HTTPS      →  https://localhost:${EddiHttpsPort}"
    Write-Information -MessageData "  MCP        →  http://localhost:${EddiPort}/mcp"
    Write-Information -MessageData "  API docs   →  http://localhost:${EddiPort}/q/swagger-ui"

    if ($WithAuth) {
        Write-Information -MessageData "  Keycloak   →  http://localhost:8180  (admin/admin)"
    }

    Write-Information -MessageData ""
    Write-Information -MessageData "  ┌─ 🔑 Vault Master Key ──────────────────────────────┐"
    Write-Information -MessageData "  │                                                    │"
    Write-Information -MessageData "  │  Stored in: $EddiDir\.env"
    Write-Information -MessageData "  │  Back up this file! If lost, encrypted             │"
    Write-Information -MessageData "  │  secrets (API keys) are unrecoverable.             │"
    Write-Information -MessageData "  └────────────────────────────────────────────────────┘"
    Write-Information -MessageData ""
    Write-Information -MessageData "  🤖 Ready to create your first agent?"
    Write-Information -MessageData "     Open the dashboard and chat with Agent Father!"
    Write-Information -MessageData "     It will guide you through choosing an AI provider,"
    Write-Information -MessageData "     setting up API keys, and building your first agent."
    Write-Information -MessageData ""
    Write-Information -MessageData "  ┌─ Claude Desktop / Cursor ──────────────────────────┐"
    Write-Information -MessageData "  │ Add to your MCP config:                            │"
    Write-Information -MessageData "  │   `"eddi`": { `"url`": `"http://localhost:${EddiPort}/mcp`" }   │"
    Write-Information -MessageData "  └────────────────────────────────────────────────────┘"
    Write-Information -MessageData ""
    Write-Information -MessageData "  Install dir: $EddiDir"
    Write-Information -MessageData ""

    # Open browser
    try { Start-Process "http://localhost:${EddiPort}" } catch { Write-Verbose "Browser failed to launch: $($_.Exception.Message)" }
}

# ── Config summary ───────────────────────────────────────

function Write-ConfigSummary {
    Write-Section "Configuration"
    $dbLabel = if ($Database -eq "postgres") { "PostgreSQL" } else { "MongoDB" }
    Write-Information -MessageData "  Database:       $dbLabel"
    Write-Information -MessageData "  Vault:          🔒 enabled (unique key)"
    $authLabel = if ($WithAuth) { "Keycloak" } else { "open access" }
    Write-Information -MessageData "  Authentication: $authLabel"
    $monLabel = if ($WithMonitoring) { "Grafana + Prometheus" } else { "none" }
    Write-Information -MessageData "  Monitoring:     $monLabel"
    Write-Information -MessageData "  HTTP port:      $EddiPort"
    Write-Information -MessageData "  HTTPS port:     $EddiHttpsPort"
    Write-Information -MessageData "  Install dir:    $EddiDir"
}

# ── Main ─────────────────────────────────────────────────

$startTime = Get-Date

try {
    Write-Banner
    Test-Prerequisites

    if ($EddiAlreadyRunning) {
        Write-Section "EDDI Already Running"
        Import-InitialAgents
        Write-Success
        Install-CliWrapper
        exit 0
    }

    Step-Database
    Step-Security
    Step-Auth
    Step-Monitoring
    Step-Ports

    Write-ConfigSummary
    Get-ComposeFiles

    Start-Eddi
    Wait-ForReady
    Import-InitialAgents
    Write-Success
    $elapsed = [math]::Round(((Get-Date) - $startTime).TotalSeconds)
    Write-Information -MessageData "  Total setup time: ${elapsed}s"
    Write-Information -MessageData ""
}
catch {
    if ($ContainersStarted -and -not $Healthy) {
        Write-Information -MessageData ""
        Write-Information -MessageData "⚠️  Setup interrupted. Cleaning up containers..."
        $envFile = Join-Path -Path $EddiDir -ChildPath ".env"
        $cleanupArgs = @("compose")
        if (Test-Path $envFile) { $cleanupArgs += @("--env-file", $envFile) }
        $cleanupArgs += ($ComposeFiles | ForEach-Object { @("-f", $_) }) + @("down")
        & docker @cleanupArgs 2>$null
    }
    throw
}

# ── Install CLI wrapper ─────────────────────────────────

function Install-CliWrapper {
    $cliPath = Join-Path -Path $EddiDir -ChildPath "eddi.cmd"
    $cliContent = @"
@echo off
setlocal EnableDelayedExpansion
set "EDDI_DIR=$EddiDir"
set "ENV_FILE=%EDDI_DIR%\.env"
set "CONFIG_FILE=%EDDI_DIR%\.eddi-config"

if not exist "%ENV_FILE%" (
    echo EDDI not installed. Run the install script first.
    exit /b 1
)

rem Load config (only COMPOSE_FILES, EDDI_PORT, EDDI_HTTPS_PORT — no secrets)
for /f "usebackq tokens=1,* delims==" %%A in ("%CONFIG_FILE%") do set "%%A=%%B"

rem Build compose flags with delayed expansion for proper quoting
set "FLAGS=--env-file "%ENV_FILE%""
for %%F in (%COMPOSE_FILES%) do set "FLAGS=!FLAGS! -f "%%F""

if "%~1"=="start"     goto cmd_start
if "%~1"=="stop"      goto cmd_stop
if "%~1"=="restart"   goto cmd_restart
if "%~1"=="status"    goto cmd_status
if "%~1"=="logs"      goto cmd_logs
if "%~1"=="update"    goto cmd_update
if "%~1"=="uninstall" goto cmd_uninstall
goto cmd_help

:cmd_start
docker compose %FLAGS% up -d
echo EDDI started on port %EDDI_PORT%
goto :eof

:cmd_stop
docker compose %FLAGS% down
echo EDDI stopped.
goto :eof

:cmd_restart
docker compose %FLAGS% down
docker compose %FLAGS% up -d
echo EDDI restarted.
goto :eof

:cmd_status
echo Containers:
docker compose %FLAGS% ps
echo.
curl -sf http://localhost:%EDDI_PORT%/q/health/ready >nul 2>&1 && (
    echo Health: ready
    for /f %%N in ('curl -sf http://localhost:%EDDI_PORT%/administration/production/deploymentstatus 2^>nul ^| findstr /c:"agentId" ^| find /c /v ""') do echo Deployed agents: %%N
) || (
    echo Health: not ready
)
goto :eof

:cmd_logs
shift
docker compose %FLAGS% logs %1 %2 %3 %4 %5
goto :eof

:cmd_update
echo Pulling latest images...
docker compose %FLAGS% pull
docker compose %FLAGS% up -d
echo EDDI updated.
goto :eof

:cmd_uninstall
echo This will stop EDDI and remove all data.
set /p CONFIRM="Are you sure? [y/N]: "
if /i "%CONFIRM%"=="y" (
    docker compose %FLAGS% down -v
    rmdir /s /q "%EDDI_DIR%"
    echo EDDI uninstalled.
)
goto :eof

:cmd_help
echo EDDI CLI
echo.
echo Usage: eddi ^<command^>
echo.
echo Commands:
echo   start       Start EDDI containers
echo   stop        Stop EDDI containers
echo   restart     Restart EDDI containers
echo   status      Show health and agent count
echo   logs [-f]   View container logs
echo   update      Pull latest images and restart
echo   uninstall   Remove EDDI and all data
goto :eof
"@
    $cliContent | Set-Content -Path $cliPath -Encoding ASCII

    # Add to user PATH if not already there (use exact boundary match)
    try {
        $userPath = [Environment]::GetEnvironmentVariable('PATH', 'User')
        $pathEntries = $userPath -split ';'
        if ($pathEntries -notcontains $EddiDir) {
            [Environment]::SetEnvironmentVariable('PATH', "$userPath;$EddiDir", 'User')
            Write-Ok "CLI wrapper installed (eddi.cmd). Restart terminal to use 'eddi' command."
        }
    }
    catch {
        Write-Information -MessageData "  Tip: Add $EddiDir to your PATH to use 'eddi' command"
    }
}

Install-CliWrapper
