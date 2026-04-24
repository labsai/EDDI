#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
#  E.D.D.I — One-Command Install & Onboarding Wizard
#
#  Usage:
#    curl -fsSL https://raw.githubusercontent.com/labsai/EDDI/main/install.sh | bash
#
#  Options (non-interactive):
#    bash install.sh --defaults                 # all defaults, no prompts
#    bash install.sh --db=postgres --with-auth  # specific choices
# ─────────────────────────────────────────────────────────────
set -euo pipefail

# ── Configuration ──────────────────────────────────────────
EDDI_BRANCH="${EDDI_BRANCH:-main}"
EDDI_PORT="${EDDI_PORT:-7070}"
EDDI_HTTPS_PORT="${EDDI_HTTPS_PORT:-7443}"
EDDI_DIR="${EDDI_DIR:-$HOME/.eddi}"
# Strip trailing slash to avoid double-slash paths in output/config
EDDI_DIR="${EDDI_DIR%/}"
# Validate branch name (prevent path traversal in download URLs)
if [[ ! "$EDDI_BRANCH" =~ ^[a-zA-Z0-9._/-]+$ ]]; then
  echo "Invalid EDDI_BRANCH: $EDDI_BRANCH" >&2; exit 1
fi
COMPOSE_BASE_URL="https://raw.githubusercontent.com/labsai/EDDI/${EDDI_BRANCH}"
EDDI_ALREADY_RUNNING=false

# ── State flags ────────────────────────────────────────────
CONTAINERS_STARTED=false
HEALTHY=false
COMPOSE_FILES=()

# ── Colors ─────────────────────────────────────────────────
if [[ -t 1 ]]; then
  BOLD='\033[1m'
  GREEN='\033[0;32m'
  YELLOW='\033[0;33m'
  RED='\033[0;31m'
  CYAN='\033[0;36m'
  DIM='\033[2m'
  RESET='\033[0m'
else
  BOLD='' GREEN='' YELLOW='' RED='' CYAN='' DIM='' RESET=''
fi

# ── Cleanup trap ───────────────────────────────────────────
cleanup() {
  if [[ "$CONTAINERS_STARTED" == "true" && "$HEALTHY" != "true" ]]; then
    echo ""
    echo -e "${YELLOW}⚠️  Setup interrupted. Cleaning up containers...${RESET}"
    if [[ ${#COMPOSE_FILES[@]} -gt 0 ]]; then
      local env_file="$EDDI_DIR/.env"
      local flags=()
      if [[ -f "$env_file" ]]; then
        flags+=(--env-file "$env_file")
      fi
      for f in "${COMPOSE_FILES[@]}"; do
        flags+=(-f "$f")
      done
      docker compose "${flags[@]}" down 2>/dev/null || true
    fi
  fi
}
trap cleanup EXIT INT TERM

# ── Helpers ────────────────────────────────────────────────
info()    { echo -e "  ${GREEN}✅${RESET} $1"; }
warn()    { echo -e "  ${YELLOW}⚠️  $1${RESET}"; }
fail()    { echo -e "  ${RED}❌ $1${RESET}"; exit 1; }
step()    { echo -e "\n${BOLD}─── Step $1 of $TOTAL_STEPS: $2 ${RESET}───────────────────────"; echo ""; }
section() { echo -e "\n${BOLD}─── $1 ${RESET}───────────────────────────────"; echo ""; }

# ask DEFAULT VALID_VALUES...
# Prompts user, validates input, returns choice on stdout
ask() {
  local default="$1"
  shift
  local valid=("$@")
  if [[ "$NON_INTERACTIVE" == "true" ]]; then
    echo "$default"
    return
  fi
  while true; do
    echo -ne "  Choose [${default}]: " >&2
    local reply
    read -r reply </dev/tty 2>/dev/null || reply=""
    reply="${reply:-$default}"
    # Validate if valid values were provided
    if [[ ${#valid[@]} -gt 0 ]]; then
      for v in "${valid[@]}"; do
        if [[ "$reply" == "$v" ]]; then
          echo "$reply"
          return
        fi
      done
      echo -e "  ${YELLOW}Please enter one of: ${valid[*]}${RESET}" >&2
    else
      echo "$reply"
      return
    fi
  done
}

# Check if a TCP port is in use (fallback chain: ss → lsof → nc)
port_in_use() {
  local port="$1"
  if command -v ss &>/dev/null; then
    # Use space/end-of-line anchor to avoid matching port 70 when checking 7070
    ss -tln 2>/dev/null | grep -qE ":${port}( |$)" && return 0
  elif command -v lsof &>/dev/null; then
    lsof -iTCP:"${port}" -sTCP:LISTEN &>/dev/null && return 0
  elif command -v nc &>/dev/null; then
    nc -z 127.0.0.1 "${port}" 2>/dev/null && return 0
  else
    # Last resort: /dev/tcp (bash built-in)
    (echo >/dev/tcp/127.0.0.1/"${port}") 2>/dev/null && return 0
  fi
  return 1
}

find_next_free_port() {
  local start="$1"
  for ((p=start; p<=start+100; p++)); do
    if ! port_in_use "$p"; then
      echo "$p"
      return
    fi
  done
  echo "0"
}

# Prompt the user for a port. Shows conflict info and suggests alternatives.
# Returns the chosen port on stdout.
# Usage: EDDI_PORT=$(read_port "HTTP" "$EDDI_PORT")
read_port() {
  local port_name="$1"
  local default_port="$2"
  local suggested="$default_port"

  if port_in_use "$default_port"; then
    warn "Port ${default_port} is already in use!" >&2
    local next_free
    next_free=$(find_next_free_port $((default_port + 1)))
    if [[ "$next_free" != "0" ]]; then
      echo -e "  Suggested alternative: ${CYAN}${next_free}${RESET}" >&2
      suggested="$next_free"
    fi
  else
    echo -e "  ${DIM}Port ${default_port} is available.${RESET}" >&2
  fi

  echo "" >&2

  if [[ "$NON_INTERACTIVE" == "true" ]]; then
    info "${port_name} port: ${suggested}" >&2
    echo "$suggested"
    return
  fi

  while true; do
    echo -ne "  ${port_name} port [${suggested}]: " >&2
    local reply
    read -r reply </dev/tty 2>/dev/null || reply=""
    reply="${reply:-$suggested}"
    if [[ "$reply" =~ ^[0-9]+$ ]] && (( reply >= 1024 && reply <= 65535 )); then
      if port_in_use "$reply"; then
        warn "Port ${reply} is in use. Try another." >&2
      else
        info "${port_name} port: ${reply}" >&2
        echo "$reply"
        return
      fi
    else
      echo -e "  ${YELLOW}Please enter a valid port (1024-65535)${RESET}" >&2
    fi
  done
}

banner() {
  echo ""
  echo -e "${BOLD}     ______   ____    ____    ____  ${RESET}"
  echo -e "${BOLD}    / ____/  / __ \\  / __ \\  /  _/ ${RESET}"
  echo -e "${BOLD}   / __/    / / / / / / / /  / /   ${RESET}"
  echo -e "${BOLD}  / /___   / /_/ / / /_/ / _/ /    ${RESET}"
  echo -e "${BOLD} /_____/  /_____/ /_____/ /___/    ${RESET}"
  echo ""
  echo -e "   ${BOLD}Multi-Agent Orchestration Middleware${RESET}"
  echo -e "   ${DIM}https://eddi.labs.ai${RESET}"
  echo ""
}

# ── Parse arguments ────────────────────────────────────────
NON_INTERACTIVE=false
DB_CHOICE=""
WITH_AUTH=false
WITH_MONITORING=false
LOCAL_IMAGE=false
VAULT_KEY_ARG=""

# Detect piped stdin (curl | bash) — disable interactive prompts
if [[ ! -t 0 ]]; then
  NON_INTERACTIVE=true
fi

for arg in "$@"; do
  case "$arg" in
    --defaults)       NON_INTERACTIVE=true; DB_CHOICE="${DB_CHOICE:-1}" ;;
    --db=mongo*)      DB_CHOICE="1" ;;
    --db=postgres*)   DB_CHOICE="2" ;;
    --with-auth)      WITH_AUTH=true ;;
    --with-monitoring) WITH_MONITORING=true ;;
    --vault-key=*)    VAULT_KEY_ARG="${arg#*=}" ;;
    --full)           DB_CHOICE="2"; WITH_AUTH=true; WITH_MONITORING=true ;;
    --local)          LOCAL_IMAGE=true ;;
    --help|-h)
      echo "EDDI Install Script"
      echo ""
      echo "Usage: curl -fsSL .../install.sh | bash"
      echo "       bash install.sh [OPTIONS]"
      echo ""
      echo "Options:"
      echo "  --defaults          Accept all defaults (non-interactive)"
      echo "  --db=mongodb        Use MongoDB (default)"
      echo "  --db=postgres       Use PostgreSQL"
      echo "  --vault-key=<key>   Set vault master key (min 16 chars)"
      echo "  --with-auth         Include Keycloak authentication"
      echo "  --with-monitoring   Include Grafana + Prometheus"
      echo "  --full              All options enabled"
      echo "  --local             Use locally built Docker image (skip pull)"
      echo ""
      echo "Environment variables:"
      echo "  EDDI_PORT           HTTP port (default: 7070)"
      echo "  EDDI_HTTPS_PORT     HTTPS port (default: 7443)"
      echo "  EDDI_DIR            Install directory (default: ~/.eddi)"
      exit 0
      ;;
  esac
done

# ── Pre-flight checks ─────────────────────────────────────

detect_platform() {
  PLATFORM="unknown"
  if [[ "$OSTYPE" == "linux-gnu"* ]]; then
    if grep -qi microsoft /proc/version 2>/dev/null; then
      PLATFORM="wsl"
    else
      PLATFORM="linux"
    fi
  elif [[ "$OSTYPE" == "darwin"* ]]; then
    PLATFORM="macos"
  fi
}

install_docker_linux() {
  echo ""
  echo -e "  ${BOLD}Docker is required but not installed.${RESET}"
  echo ""

  if [[ "$NON_INTERACTIVE" == "true" ]]; then
    echo "  Install Docker first: https://docs.docker.com/get-docker/"
    exit 1
  fi

  echo -ne "  Install Docker now? [Y/n]: " >&2
  local reply
  read -r reply </dev/tty 2>/dev/null || reply="y"
  reply="${reply:-y}"

  if [[ "$reply" =~ ^[Yy]$ ]]; then
    echo -e "  Installing Docker via ${CYAN}get.docker.com${RESET}..."
    if curl -fsSL https://get.docker.com | sh; then
      info "Docker installed!"
      # Add current user to docker group (takes effect on next login)
      if ! groups | grep -q docker; then
        sudo usermod -aG docker "$USER" 2>/dev/null || true
        echo -e "  ${YELLOW}Note: You may need to log out/in for Docker group to take effect.${RESET}"
        echo -e "  ${YELLOW}      If docker commands fail, run: newgrp docker${RESET}"
      fi
    else
      fail "Docker installation failed. Try manually: https://docs.docker.com/get-docker/"
    fi
  else
    echo "  Install Docker first, then re-run this script."
    exit 1
  fi
}

check_prerequisites() {
  echo ""
  detect_platform

  # curl
  if ! command -v curl &>/dev/null; then
    fail "curl is required but not found.\n     Install: apt install curl / brew install curl"
  fi

  # jq (optional but used for agent count check)
  if ! command -v jq &>/dev/null; then
    JQ_AVAILABLE=false
  else
    JQ_AVAILABLE=true
  fi

  # Docker
  if ! command -v docker &>/dev/null; then
    case "$PLATFORM" in
      linux|wsl)
        install_docker_linux
        ;;
      macos)
        echo -e "  ${RED}❌ Docker is not installed.${RESET}"
        echo ""
        echo "     Install Docker Desktop for macOS:"
        echo "       brew install --cask docker"
        echo "       — or —"
        echo "       https://docs.docker.com/desktop/install/mac-install/"
        echo ""
        echo "     After installing, open Docker Desktop and wait for it to start."
        exit 1
        ;;
      *)
        echo -e "  ${RED}❌ Docker is not installed.${RESET}"
        echo ""
        echo "     Install Docker: https://docs.docker.com/get-docker/"
        exit 1
        ;;
    esac
  fi
  info "Docker found ($(docker --version | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1))"

  # Docker daemon running
  if ! docker info &>/dev/null 2>&1; then
    case "$PLATFORM" in
      macos)
        fail "Docker is installed but not running.\n     Open Docker Desktop from Applications." ;;
      linux|wsl)
        fail "Docker is installed but not running.\n     Run: sudo systemctl start docker" ;;
      *)
        fail "Docker is installed but not running.\n     Start Docker Desktop or the Docker daemon." ;;
    esac
  fi

  # Docker Compose
  if docker compose version &>/dev/null 2>&1; then
    info "Docker Compose found ($(docker compose version --short 2>/dev/null))"
  else
    fail "Docker Compose not found.\n     Docker Compose is included with Docker Desktop.\n     Linux: sudo apt install docker-compose-plugin"
  fi

  # Disk space check (need ~2GB for images)
  local available_gb
  if [[ "$OSTYPE" == "darwin"* ]]; then
    # macOS df doesn't support -BG, use -g for GB blocks
    available_gb=$(df -g "$HOME" 2>/dev/null | awk 'NR==2{print $4}') || available_gb=999
  else
    available_gb=$(df -BG "$HOME" 2>/dev/null | awk 'NR==2{print $4}' | tr -d 'G') || available_gb=999
  fi
  if [[ "$available_gb" =~ ^[0-9]+$ ]] && [[ "$available_gb" -lt 3 ]]; then
    warn "Low disk space (${available_gb}GB free). EDDI images need ~2GB."
    warn "Free space: docker system prune"
  fi

  # Port check — is EDDI already running?
  if curl -sf "http://localhost:${EDDI_PORT}/q/health/ready" &>/dev/null 2>&1; then
    EDDI_ALREADY_RUNNING=true
    info "EDDI already running on port ${EDDI_PORT}"
  else
    EDDI_ALREADY_RUNNING=false
  fi
}

# ── Detect existing state ─────────────────────────────────

detect_deployed_agents() {
  local count=0
  local response
  response=$(curl -sf "http://localhost:${EDDI_PORT}/administration/production/deploymentstatus" 2>/dev/null) || return 1

  if [[ "$JQ_AVAILABLE" == "true" ]]; then
    count=$(echo "$response" | jq 'length' 2>/dev/null) || count=0
  else
    # Fallback: count array elements by counting "agentId" occurrences
    count=$(echo "$response" | grep -o '"agentId"' | wc -l | tr -d ' ') || count=0
  fi

  echo "$count"
}

# ── Wizard steps ───────────────────────────────────────────

TOTAL_STEPS=5
EDDI_VAULT_MASTER_KEY=""

wizard_database() {
  if [[ -n "$DB_CHOICE" ]]; then return; fi

  step 1 "Database"
  echo "  EDDI needs a database to store agent configs & conversations."
  echo ""
  echo -e "  ${BOLD}1)${RESET} MongoDB        ${DIM}document store, simple setup (default)${RESET}"
  echo -e "  ${BOLD}2)${RESET} PostgreSQL     ${DIM}relational, SQL-queryable, familiar${RESET}"
  echo ""
  DB_CHOICE=$(ask "1" "1" "2")
}

# Generate a cryptographically random vault key (32 base64 chars)
generate_vault_key() {
  if command -v openssl &>/dev/null; then
    openssl rand -base64 24
  else
    # Fallback: read from /dev/urandom
    head -c 24 /dev/urandom | base64
  fi
}

wizard_security() {
  # If a key was provided via CLI arg, use it
  if [[ -n "$VAULT_KEY_ARG" ]]; then
    if [[ ${#VAULT_KEY_ARG} -lt 16 ]]; then
      fail "Vault key must be at least 16 characters (got ${#VAULT_KEY_ARG})"
    fi
    EDDI_VAULT_MASTER_KEY="$VAULT_KEY_ARG"
    return
  fi

  # If a key already exists from a previous install, preserve it
  local env_file="$EDDI_DIR/.env"
  if [[ -f "$env_file" ]]; then
    local existing_key
    existing_key=$(sed -n 's/^EDDI_VAULT_MASTER_KEY=//p' "$env_file" 2>/dev/null || true)
    # Strip surrounding double quotes to prevent quote accumulation on re-runs
    existing_key="${existing_key#\"}"
    existing_key="${existing_key%\"}"
    if [[ -n "$existing_key" ]]; then
      EDDI_VAULT_MASTER_KEY="$existing_key"
      info "Vault key preserved from previous install" >&2
      return
    fi
  fi

  step 2 "Security"
  echo "  EDDI encrypts API keys and secrets using a vault master key."
  echo "  This key is unique to your installation — keep it safe!"
  echo ""

  if [[ "$NON_INTERACTIVE" == "true" ]]; then
    # Auto-generate for non-interactive installs
    EDDI_VAULT_MASTER_KEY=$(generate_vault_key)
    info "Vault master key auto-generated"
    return
  fi

  echo -e "  ${BOLD}1)${RESET} Auto-generate  ${DIM}strong random key (recommended)${RESET}"
  echo -e "  ${BOLD}2)${RESET} Custom         ${DIM}enter your own passphrase (min 16 chars)${RESET}"
  echo ""
  local sec_choice
  sec_choice=$(ask "1" "1" "2")

  if [[ "$sec_choice" == "1" ]]; then
    EDDI_VAULT_MASTER_KEY=$(generate_vault_key)
    info "Vault master key generated"
  else
    while true; do
      echo -ne "  Enter passphrase: " >&2
      local passphrase
      read -rs passphrase </dev/tty 2>/dev/null || passphrase=""
      echo "" >&2
      if [[ ${#passphrase} -lt 16 ]]; then
        echo -e "  ${YELLOW}Passphrase must be at least 16 characters${RESET}" >&2
      else
        EDDI_VAULT_MASTER_KEY="$passphrase"
        info "Custom passphrase set"
        break
      fi
    done
  fi
}

wizard_auth() {
  if [[ "$NON_INTERACTIVE" == "true" ]]; then return; fi
  if [[ "$WITH_AUTH" == "true" ]]; then return; fi

  step 3 "Authentication"
  echo "  How should EDDI handle user access?"
  echo ""
  echo -e "  ${BOLD}1)${RESET} Open access    ${DIM}no login needed (dev / personal)${RESET}"
  echo -e "  ${BOLD}2)${RESET} Keycloak       ${DIM}multi-user OIDC (production)${RESET}"
  echo ""
  local auth_choice
  auth_choice=$(ask "1" "1" "2")
  [[ "$auth_choice" == "2" ]] && WITH_AUTH=true
}

wizard_monitoring() {
  if [[ "$NON_INTERACTIVE" == "true" ]]; then return; fi
  if [[ "$WITH_MONITORING" == "true" ]]; then return; fi

  step 4 "Monitoring"
  echo -e "  ${BOLD}1)${RESET} Skip for now   ${DIM}add later with: eddi update --with-monitoring${RESET}"
  echo -e "  ${BOLD}2)${RESET} Grafana        ${DIM}dashboards + Prometheus metrics${RESET}"
  echo ""
  local mon_choice
  mon_choice=$(ask "1" "1" "2")
  [[ "$mon_choice" == "2" ]] && WITH_MONITORING=true
}

wizard_ports() {
  step 5 "Ports"
  echo "  EDDI uses two ports: HTTP for the dashboard/API, HTTPS for secure access."
  echo ""

  EDDI_PORT=$(read_port "HTTP" "$EDDI_PORT")
  EDDI_HTTPS_PORT=$(read_port "HTTPS" "$EDDI_HTTPS_PORT")
}

# ── Compose file management ───────────────────────────────

# Detect script directory (empty when piped via curl | bash)
# Guard: when piped, BASH_SOURCE is empty and dirname resolves to CWD,
# which could contain unrelated files (e.g. ~/docker-compose.yml).
if [[ -n "${BASH_SOURCE[0]:-}" && -f "${BASH_SOURCE[0]}" ]]; then
  SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" 2>/dev/null && pwd)" || SCRIPT_DIR=""
else
  SCRIPT_DIR=""
fi

resolve_compose_files() {
  mkdir -p "$EDDI_DIR"

  local needed_files=()
  COMPOSE_FILES=()

  # Base compose file depends on database choice
  if [[ "${DB_CHOICE:-1}" == "2" ]]; then
    needed_files+=("docker-compose.postgres-only.yml")
  else
    needed_files+=("docker-compose.yml")
  fi

  # Local build overlay (overrides image with local build context)
  if [[ "$LOCAL_IMAGE" == "true" ]]; then
    # --local requires running from the EDDI repo checkout
    EDDI_REPO_ROOT="${SCRIPT_DIR:-$(pwd)}"
    if [[ ! -f "$EDDI_REPO_ROOT/docker-compose.local.yml" ]]; then
      fail "--local requires running from the EDDI repo root.\n     Run: cd /path/to/EDDI && bash install.sh --local"
    fi
    if [[ ! -f "$EDDI_REPO_ROOT/src/main/docker/Dockerfile" ]]; then
      fail "Dockerfile not found. Run: ./mvnw package -DskipTests first."
    fi
    # Use the file directly from the repo (build context must be repo root)
    COMPOSE_FILES+=("$EDDI_REPO_ROOT/docker-compose.local.yml")
  fi

  # Auth overlay
  if [[ "$WITH_AUTH" == "true" ]]; then
    needed_files+=("docker-compose.auth.yml")
  fi

  # Monitoring overlay
  if [[ "$WITH_MONITORING" == "true" ]]; then
    needed_files+=("docker-compose.monitoring.yml")
  fi

  # Resolve each file: prefer local copy, fall back to download
  for f in "${needed_files[@]}"; do
    local target="$EDDI_DIR/$f"

    if [[ -n "$SCRIPT_DIR" && -f "$SCRIPT_DIR/$f" ]]; then
      # File exists next to the script — copy to install dir
      cp "$SCRIPT_DIR/$f" "$target"
      echo -e "  Using local ${f} ${GREEN}✅${RESET}"
    else
      # Not available locally — download from GitHub
      local download_url="${COMPOSE_BASE_URL}/${f}"
      echo -ne "  Downloading ${f}... "
      if curl -fsSL "${download_url}" -o "$target"; then
        echo -e "${GREEN}✅${RESET}"
      else
        fail "Failed to download ${f}.\n     URL: ${download_url}\n     Check your internet connection and that the branch '${EDDI_BRANCH}' exists."
      fi
    fi

    COMPOSE_FILES+=("$target")
  done

  # Download monitoring support files if needed
  if [[ "$WITH_MONITORING" == "true" ]]; then
    echo ""
    echo -e "  ${DIM}Downloading monitoring configuration...${RESET}"
    local monitoring_files=(
      "docs/monitoring/prometheus.yml"
      "docs/monitoring/grafana-provisioning/dashboards/dashboards.yml"
      "docs/monitoring/grafana-provisioning/datasources/datasources.yml"
      "docs/monitoring/eddi-grafana-dashboard.json"
    )
    for mf in "${monitoring_files[@]}"; do
      local mf_target="$EDDI_DIR/$mf"
      local mf_dir
      mf_dir=$(dirname "$mf_target")
      mkdir -p "$mf_dir"

      if [[ -n "$SCRIPT_DIR" && -f "$SCRIPT_DIR/$mf" ]]; then
        cp "$SCRIPT_DIR/$mf" "$mf_target"
      else
        local mf_url="${COMPOSE_BASE_URL}/${mf}"
        echo -ne "  Downloading ${mf}... "
        if curl -fsSL "${mf_url}" -o "$mf_target"; then
          echo -e "${GREEN}✅${RESET}"
        else
          warn "Failed to download ${mf} (monitoring may not work)"
        fi
      fi
    done
  fi

  # Download Keycloak realm if auth enabled
  if [[ "$WITH_AUTH" == "true" ]]; then
    local kc_files=("keycloak/eddi-realm.json")
    for kf in "${kc_files[@]}"; do
      local kf_target="$EDDI_DIR/$kf"
      local kf_dir
      kf_dir=$(dirname "$kf_target")
      mkdir -p "$kf_dir"

      if [[ -n "$SCRIPT_DIR" && -f "$SCRIPT_DIR/$kf" ]]; then
        cp "$SCRIPT_DIR/$kf" "$kf_target"
        echo -e "  Using local ${kf} ${GREEN}✅${RESET}"
      else
        local kf_url="${COMPOSE_BASE_URL}/${kf}"
        echo -ne "  Downloading ${kf}... "
        if curl -fsSL "${kf_url}" -o "$kf_target"; then
          echo -e "${GREEN}✅${RESET}"
        else
          fail "Failed to download ${kf} (required for Keycloak).\n     URL: ${kf_url}"
        fi
      fi
    done
  fi

  # Save config for eddi CLI wrapper (no secrets — vault key stays in .env only)
  echo "COMPOSE_FILES=${COMPOSE_FILES[*]}" > "$EDDI_DIR/.eddi-config"
  echo "EDDI_PORT=$EDDI_PORT" >> "$EDDI_DIR/.eddi-config"
  echo "EDDI_HTTPS_PORT=$EDDI_HTTPS_PORT" >> "$EDDI_DIR/.eddi-config"
  echo "EDDI_BRANCH=$EDDI_BRANCH" >> "$EDDI_DIR/.eddi-config"

  # Write .env file for docker compose variable substitution
  # Escape double quotes in vault key to prevent .env corruption
  local escaped_key="${EDDI_VAULT_MASTER_KEY//\"/\\\"}"
  local db_txt="mongodb"
  if [[ "${DB_CHOICE:-1}" == "2" ]]; then
    db_txt="postgres"
  fi
  cat > "$EDDI_DIR/.env" <<EOF
# EDDI environment — generated by installer
# ⚠️  The vault master key encrypts all stored API keys.
#     If you lose this key, encrypted secrets are UNRECOVERABLE.
EDDI_VAULT_MASTER_KEY="${escaped_key}"
EDDI_DATASTORE_TYPE=${db_txt}
EDDI_PORT=$EDDI_PORT
EDDI_HTTPS_PORT=$EDDI_HTTPS_PORT
EOF
  # Restrict permissions on sensitive files (owner-only read/write)
  chmod 600 "$EDDI_DIR/.env"
  chmod 600 "$EDDI_DIR/.eddi-config"
}

# Helper: run docker compose with the right -f flags
compose_cmd() {
  local flags=(--env-file "$EDDI_DIR/.env")
  for f in "${COMPOSE_FILES[@]}"; do
    flags+=(-f "$f")
  done
  docker compose "${flags[@]}" "$@"
}

# ── Start containers ──────────────────────────────────────

start_eddi() {
  section "Starting EDDI"

  # Export port env vars so docker-compose variable substitution picks them up
  # Note: vault key is NOT exported — it's read from --env-file only
  export EDDI_PORT
  export EDDI_HTTPS_PORT

  if [[ "$LOCAL_IMAGE" == "true" ]]; then
    echo "  Building local Docker image..."
    echo ""
    if compose_cmd build 2>&1 | sed 's/^/    /'; then
      echo ""
      info "Local image built"
    else
      fail "Failed to build local image.\n     Make sure you ran: ./mvnw package -DskipTests"
    fi
  else
    echo "  Pulling images (this may take a minute)..."
    echo ""
    if compose_cmd pull 2>&1 | sed 's/^/    /'; then
      echo ""
      info "Images pulled"
    else
      fail "Failed to pull images. Check internet connection and disk space.\n     Run: docker system df"
    fi
  fi

  echo -ne "  Starting containers...   "
  local compose_err
  compose_err=$(mktemp "${TMPDIR:-/tmp}/eddi-compose.XXXXXX")
  if compose_cmd up -d 2>"$compose_err"; then
    echo -e "${GREEN}✅${RESET}"
    CONTAINERS_STARTED=true
  else
    echo ""
    cat "$compose_err" >&2
    rm -f "$compose_err"
    fail "Failed to start containers.\n     Check logs: docker compose logs in $EDDI_DIR"
  fi
  rm -f "$compose_err"
}

# ── Health check ──────────────────────────────────────────

wait_for_ready() {
  # Keycloak can take 60-90s to start; EDDI won't start until it's healthy
  local max_wait=120
  local elapsed=0
  echo -ne "  Health check             "

  while [[ $elapsed -lt $max_wait ]]; do
    if curl -sf "http://localhost:${EDDI_PORT}/q/health/ready" &>/dev/null; then
      echo -e "${GREEN}✅${RESET} ${DIM}ready in ${elapsed}s${RESET}"
      HEALTHY=true
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
    echo -ne "."
  done

  echo -e "${RED}timeout${RESET}"
  echo ""
  echo -e "  ${YELLOW}EDDI didn't become ready in ${max_wait}s.${RESET}"
  echo "  Check logs:"
  echo "    cd $EDDI_DIR && docker compose ${COMPOSE_FILES[*]/#/-f } logs eddi"
  echo "    cd $EDDI_DIR && docker compose ${COMPOSE_FILES[*]/#/-f } logs"
  echo ""
  echo "  Containers left running for inspection."
  # Mark healthy to prevent cleanup trap from tearing down containers
  # (we explicitly told the user containers are left running)
  HEALTHY=true
  exit 1
}

# ── Import initial agents ──────────────────────────────────

maybe_import_initial_agents() {
  local agent_count
  agent_count=$(detect_deployed_agents) || agent_count=0

  if [[ "$agent_count" -eq 0 ]]; then
    echo -ne "  Deploying Agent Father...  "
    local status
    status=$(curl -sf -o /dev/null -w "%{http_code}" \
      -X POST "http://localhost:${EDDI_PORT}/backup/import/initialAgents" 2>/dev/null) || status="000"

    if [[ "$status" == "200" ]]; then
      echo -e "${GREEN}✅${RESET}"
    else
      echo -e "${YELLOW}⚠️${RESET}  ${DIM}(HTTP ${status} — non-fatal, EDDI is still usable)${RESET}"
    fi
  else
    info "Found ${agent_count} deployed agent(s), skipping initial import."
  fi
}

# ── Success banner ────────────────────────────────────────

print_success() {
  echo ""
  echo -e "${GREEN}${BOLD}─── 🎉 Setup Complete! ────────────────────────────${RESET}"
  echo ""
  echo -e "  ${BOLD}Dashboard${RESET}  →  ${CYAN}http://localhost:${EDDI_PORT}${RESET}"
  echo -e "  ${BOLD}HTTPS${RESET}      →  ${CYAN}https://localhost:${EDDI_HTTPS_PORT}${RESET}"
  echo -e "  ${BOLD}MCP${RESET}        →  ${CYAN}http://localhost:${EDDI_PORT}/mcp${RESET}"
  echo -e "  ${BOLD}API docs${RESET}   →  ${CYAN}http://localhost:${EDDI_PORT}/q/swagger-ui${RESET}"

  if [[ "$WITH_MONITORING" == "true" ]]; then
    echo ""
    echo -e "  ${BOLD}Grafana${RESET}    →  ${CYAN}http://localhost:3000${RESET}  ${DIM}(admin/admin)${RESET}"
    echo -e "  ${BOLD}Prometheus${RESET} →  ${CYAN}http://localhost:9090${RESET}"
    echo -e "  ${BOLD}Jaeger${RESET}     →  ${CYAN}http://localhost:16686${RESET}  ${DIM}(trace visualization)${RESET}"
  fi

  if [[ "$WITH_AUTH" == "true" ]]; then
    echo ""
    echo -e "  ${BOLD}┌─ 🔐 Login Credentials ─────────────────────────────┐${RESET}"
    echo -e "  ${BOLD}│${RESET}                                                    ${BOLD}│${RESET}"
    echo -e "  ${BOLD}│${RESET}  EDDI Admin:  ${CYAN}eddi / eddi${RESET}  (change on first login) ${BOLD}│${RESET}"
    echo -e "  ${BOLD}│${RESET}  Read-only:   ${CYAN}viewer / viewer${RESET}                      ${BOLD}│${RESET}"
    echo -e "  ${BOLD}│${RESET}                                                    ${BOLD}│${RESET}"
    echo -e "  ${BOLD}│${RESET}  Keycloak Console:  ${CYAN}http://localhost:8180${RESET}           ${BOLD}│${RESET}"
    echo -e "  ${BOLD}│${RESET}  Console Admin:     ${CYAN}admin / admin${RESET}                  ${BOLD}│${RESET}"
    echo -e "  ${BOLD}└────────────────────────────────────────────────────┘${RESET}"
  fi

  echo ""
  echo -e "  ${YELLOW}┌─ 🔑 Vault Master Key ──────────────────────────────┐${RESET}"
  echo -e "  ${YELLOW}│                                                    │${RESET}"
  echo -e "  ${YELLOW}│${RESET}  Stored in: ${BOLD}${EDDI_DIR}/.env${RESET}${YELLOW}                      │${RESET}"
  echo -e "  ${YELLOW}│${RESET}  ${DIM}Back up this file! If lost, encrypted${RESET}${YELLOW}             │${RESET}"
  echo -e "  ${YELLOW}│${RESET}  ${DIM}secrets (API keys) are unrecoverable.${RESET}${YELLOW}             │${RESET}"
  echo -e "  ${YELLOW}└────────────────────────────────────────────────────┘${RESET}"
  echo ""
  echo -e "  ${BOLD}🤖 Ready to create your first agent?${RESET}"
  echo "     Open the dashboard and chat with Agent Father!"
  echo "     It will guide you through choosing an AI provider,"
  echo "     setting up API keys, and building your first agent."
  echo ""
  echo -e "  ${DIM}┌─ Claude Desktop / Cursor ──────────────────────────┐${RESET}"
  echo -e "  ${DIM}│ Add to your MCP config:                            │${RESET}"
  echo -e "  ${DIM}│   \"eddi\": { \"url\": \"http://localhost:${EDDI_PORT}/mcp\" }   │${RESET}"
  echo -e "  ${DIM}└────────────────────────────────────────────────────┘${RESET}"
  echo ""
  echo -e "  ${BOLD}Manage EDDI:${RESET}"
  echo "    eddi status     health + deployed agents"
  echo "    eddi logs       view container logs"
  echo "    eddi stop       stop all containers"
  echo "    eddi update     pull latest version"
  echo ""
  echo -e "  ${DIM}Install dir: ${EDDI_DIR}${RESET}"
  echo ""

  # Try to open browser — Manager SPA handles Keycloak login if auth is enabled
  local url="http://localhost:${EDDI_PORT}/manage"
  case "$PLATFORM" in
    macos) open "$url" 2>/dev/null || true ;;
    wsl)   explorer.exe "$url" 2>/dev/null || true ;;
    linux) xdg-open "$url" 2>/dev/null || true ;;
  esac
}

# ── Install eddi CLI wrapper ─────────────────────────────

install_cli_wrapper() {
  local cli_path="$EDDI_DIR/eddi"

  cat > "$cli_path" << 'EDDI_CLI'
#!/usr/bin/env bash
set -euo pipefail

EDDI_DIR="${EDDI_DIR:-$HOME/.eddi}"
CONFIG_FILE="$EDDI_DIR/.eddi-config"
ENV_FILE="$EDDI_DIR/.env"

if [[ ! -f "$CONFIG_FILE" ]]; then
  echo "EDDI not installed. Run the install script first."
  exit 1
fi

if [[ ! -f "$ENV_FILE" ]]; then
  echo "EDDI .env file missing. Run the install script first."
  exit 1
fi

# Safe config parsing — no source/eval to prevent code injection
_cfg() { grep "^$1=" "$CONFIG_FILE" 2>/dev/null | head -1 | cut -d= -f2- | sed 's/^"//;s/"$//'; }
EDDI_PORT=$(_cfg EDDI_PORT)
EDDI_HTTPS_PORT=$(_cfg EDDI_HTTPS_PORT)
EDDI_BRANCH="${EDDI_BRANCH:-$(_cfg EDDI_BRANCH)}"
EDDI_BRANCH="${EDDI_BRANCH:-main}"
if [[ ! "$EDDI_BRANCH" =~ ^[a-zA-Z0-9._/-]+$ ]]; then
  echo "Invalid EDDI_BRANCH: $EDDI_BRANCH" >&2
  exit 1
fi
COMPOSE_BASE_URL="https://raw.githubusercontent.com/labsai/EDDI/${EDDI_BRANCH}"

# Parse compose files into array (handles paths with spaces)
read -ra COMPOSE_FILE_LIST <<< "$(_cfg COMPOSE_FILES)"

# Build compose command args as a proper array
compose_args=(--env-file "$ENV_FILE")
for f in "${COMPOSE_FILE_LIST[@]}"; do
  compose_args+=(-f "$f")
done

case "${1:-help}" in
  start)
    docker compose "${compose_args[@]}" up -d
    echo "EDDI started on port ${EDDI_PORT}"
    ;;
  stop)
    docker compose "${compose_args[@]}" down
    echo "EDDI stopped."
    ;;
  restart)
    docker compose "${compose_args[@]}" down
    docker compose "${compose_args[@]}" up -d
    echo "EDDI restarted."
    ;;
  status)
    echo "Containers:"
    docker compose "${compose_args[@]}" ps
    echo ""
    if curl -sf "http://localhost:${EDDI_PORT}/q/health/ready" &>/dev/null; then
      echo "Health: ✅ ready"
      AGENT_COUNT=$(curl -sf "http://localhost:${EDDI_PORT}/administration/production/deploymentstatus" 2>/dev/null | grep -o '"agentId"' | wc -l || echo "0")
      echo "Deployed agents: $AGENT_COUNT"
    else
      echo "Health: ❌ not ready"
    fi
    ;;
  logs)
    shift
    docker compose "${compose_args[@]}" logs "${@}"
    ;;
  update)
    echo "Refreshing compose files from GitHub..."
    for f in "${COMPOSE_FILE_LIST[@]}"; do
      local_name=$(basename "$f")
      download_url="${COMPOSE_BASE_URL}/${local_name}"
      tmp_file="${f}.tmp"
      echo -n "  Updating ${local_name}... "
      if curl -fsSL "${download_url}" -o "$tmp_file" 2>/dev/null && [[ -s "$tmp_file" ]]; then
        mv -f "$tmp_file" "$f"
        echo "✅"
      else
        rm -f "$tmp_file"
        echo "⚠️  (keeping existing file)"
      fi
    done
    echo ""
    echo "Pulling latest images..."
    docker compose "${compose_args[@]}" pull
    docker compose "${compose_args[@]}" up -d
    echo "EDDI updated."
    ;;
  uninstall)
    # Sanity check: refuse to delete if EDDI_DIR doesn't look right
    if [[ ! "$EDDI_DIR" == *"/.eddi"* ]] && [[ ! "$EDDI_DIR" == *"\.eddi"* ]]; then
      echo "EDDI_DIR doesn't look safe to delete: $EDDI_DIR"
      exit 1
    fi
    echo "This will stop EDDI and remove all data."
    read -rp "Are you sure? [y/N]: " confirm
    if [[ "$confirm" =~ ^[Yy]$ ]]; then
      docker compose "${compose_args[@]}" down -v
      rm -rf "$EDDI_DIR"
      echo "EDDI uninstalled."
    fi
    ;;
  help|--help|-h|*)
    echo "EDDI CLI"
    echo ""
    echo "Usage: eddi <command>"
    echo ""
    echo "Commands:"
    echo "  start       Start EDDI containers"
    echo "  stop        Stop EDDI containers"
    echo "  restart     Restart EDDI containers"
    echo "  status      Show health and agent count"
    echo "  logs [-f]   View container logs"
    echo "  update      Refresh configs, pull latest images, restart"
    echo "  uninstall   Remove EDDI and all data"
    ;;
esac
EDDI_CLI

  chmod +x "$cli_path"

  # Try to symlink into PATH
  local link_target=""
  if [[ -d "$HOME/.local/bin" ]]; then
    link_target="$HOME/.local/bin/eddi"
  elif [[ -d "/usr/local/bin" && -w "/usr/local/bin" ]]; then
    link_target="/usr/local/bin/eddi"
  fi

  if [[ -n "$link_target" ]]; then
    ln -sf "$cli_path" "$link_target" 2>/dev/null || true
  else
    echo -e "  ${DIM}Tip: Add ${EDDI_DIR} to your PATH to use 'eddi' command${RESET}"
  fi
}

# ── Main ──────────────────────────────────────────────────

print_config_summary() {
  section "Configuration"
  local db_label="MongoDB"
  [[ "${DB_CHOICE:-1}" == "2" ]] && db_label="PostgreSQL"
  echo -e "  Database:       ${BOLD}${db_label}${RESET}"
  echo -e "  Vault:          ${BOLD}🔒 enabled${RESET} ${DIM}(unique key)${RESET}"
  if [[ "$WITH_AUTH" == "true" ]]; then
    echo -e "  Authentication: ${BOLD}Keycloak${RESET}"
  else
    echo -e "  Authentication: ${DIM}open access${RESET}"
  fi
  if [[ "$WITH_MONITORING" == "true" ]]; then
    echo -e "  Monitoring:     ${BOLD}Grafana + Prometheus${RESET}"
  else
    echo -e "  Monitoring:     ${DIM}none${RESET}"
  fi
  echo -e "  Port:           ${BOLD}${EDDI_PORT}${RESET} (HTTP), ${BOLD}${EDDI_HTTPS_PORT}${RESET} (HTTPS)"
  echo -e "  Install dir:    ${BOLD}${EDDI_DIR}${RESET}"
}

main() {
  local start_time
  start_time=$(date +%s)

  banner
  check_prerequisites

  if [[ "$EDDI_ALREADY_RUNNING" == "true" ]]; then
    # EDDI is already running — skip infra, check agents
    section "EDDI Already Running"
    # Ensure .eddi-config exists (may be missing if EDDI was started manually)
    if [[ ! -f "$EDDI_DIR/.eddi-config" ]]; then
      warn "No .eddi-config found — creating minimal config for CLI wrapper."
      mkdir -p "$EDDI_DIR"
      cat > "$EDDI_DIR/.eddi-config" <<CFGEOF
COMPOSE_FILES=$EDDI_DIR/docker-compose.yml
EDDI_PORT=$EDDI_PORT
EDDI_HTTPS_PORT=$EDDI_HTTPS_PORT
CFGEOF
      chmod 600 "$EDDI_DIR/.eddi-config"
    fi
    maybe_import_initial_agents
    install_cli_wrapper
    print_success
    exit 0
  fi

  # Interactive wizard
  wizard_database
  wizard_security
  wizard_auth
  wizard_monitoring
  wizard_ports

  # Show chosen config
  print_config_summary

  # Download and start
  resolve_compose_files
  start_eddi
  wait_for_ready
  maybe_import_initial_agents

  # Install CLI wrapper
  install_cli_wrapper

  # Done!
  local end_time
  end_time=$(date +%s)
  local elapsed=$(( end_time - start_time ))
  print_success
  echo -e "  ${DIM}Total setup time: ${elapsed}s${RESET}"
  echo ""
}

main "$@"
