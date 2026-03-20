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
EDDI_VERSION="${EDDI_VERSION:-6}"
EDDI_PORT="${EDDI_PORT:-7070}"
EDDI_DIR="${EDDI_DIR:-$HOME/.eddi}"
COMPOSE_BASE_URL="https://raw.githubusercontent.com/labsai/EDDI/main"
EDDI_ALREADY_RUNNING=false

# ── State flags ────────────────────────────────────────────
CONTAINERS_STARTED=false
HEALTHY=false

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
      docker compose ${COMPOSE_FILES[@]/#/-f } down 2>/dev/null || true
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

banner() {
  echo ""
  echo -e "${CYAN}${BOLD}"
  echo " ╔═══════════════════════════════════════════════════╗"
  echo " ║             E . D . D . I                         ║"
  echo " ║    Multi-Agent Orchestration Middleware            ║"
  echo " ║                                                   ║"
  echo " ║    Setup Wizard                                   ║"
  echo " ╚═══════════════════════════════════════════════════╝"
  echo -e "${RESET}"
}

# ── Parse arguments ────────────────────────────────────────
NON_INTERACTIVE=false
DB_CHOICE=""
WITH_AUTH=false
WITH_MONITORING=false

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
    --full)           DB_CHOICE="2"; WITH_AUTH=true; WITH_MONITORING=true ;;
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
      echo "  --with-auth         Include Keycloak authentication"
      echo "  --with-monitoring   Include Grafana + Prometheus"
      echo "  --full              All options enabled"
      echo ""
      echo "Environment variables:"
      echo "  EDDI_PORT           Port for EDDI (default: 7070)"
      echo "  EDDI_DIR            Install directory (default: ~/.eddi)"
      echo "  EDDI_VERSION        Docker image tag (default: 6)"
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

  # jq (optional but used for bot count check)
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
  available_gb=$(df -BG "$HOME" 2>/dev/null | awk 'NR==2{print $4}' | tr -d 'G') || available_gb=999
  if [[ "$available_gb" =~ ^[0-9]+$ ]] && [[ "$available_gb" -lt 3 ]]; then
    warn "Low disk space (${available_gb}GB free). EDDI images need ~2GB."
    warn "Free space: docker system prune"
  fi

  # Port check — is something already on EDDI_PORT?
  if curl -sf "http://localhost:${EDDI_PORT}/q/health/ready" &>/dev/null 2>&1; then
    EDDI_ALREADY_RUNNING=true
    info "EDDI already running on port ${EDDI_PORT}"
  elif curl -sf "http://localhost:${EDDI_PORT}" &>/dev/null 2>&1; then
    fail "Port ${EDDI_PORT} is in use by another service.\n     Use: EDDI_PORT=7071 bash install.sh"
  else
    EDDI_ALREADY_RUNNING=false
  fi
}

# ── Detect existing state ─────────────────────────────────

detect_deployed_bots() {
  local count=0
  local response
  response=$(curl -sf "http://localhost:${EDDI_PORT}/administration/unrestricted/deploymentstatus" 2>/dev/null) || return 1

  if [[ "$JQ_AVAILABLE" == "true" ]]; then
    count=$(echo "$response" | jq 'length' 2>/dev/null) || count=0
  else
    # Fallback: count array elements by counting "botId" occurrences
    count=$(echo "$response" | grep -o '"botId"' | wc -l | tr -d ' ') || count=0
  fi

  echo "$count"
}

# ── Wizard steps ───────────────────────────────────────────

TOTAL_STEPS=3

wizard_database() {
  if [[ -n "$DB_CHOICE" ]]; then return; fi

  step 1 "Database"
  echo "  EDDI needs a database to store bot configs & conversations."
  echo ""
  echo -e "  ${BOLD}1)${RESET} MongoDB        ${DIM}document store, simple setup (default)${RESET}"
  echo -e "  ${BOLD}2)${RESET} PostgreSQL     ${DIM}relational, SQL-queryable, familiar${RESET}"
  echo ""
  DB_CHOICE=$(ask "1" "1" "2")
}

wizard_auth() {
  if [[ "$NON_INTERACTIVE" == "true" ]]; then return; fi
  if [[ "$WITH_AUTH" == "true" ]]; then return; fi

  step 2 "Authentication"
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

  step 3 "Monitoring"
  echo -e "  ${BOLD}1)${RESET} Skip for now   ${DIM}add later with: eddi update --with-monitoring${RESET}"
  echo -e "  ${BOLD}2)${RESET} Grafana        ${DIM}dashboards + Prometheus metrics${RESET}"
  echo ""
  local mon_choice
  mon_choice=$(ask "1" "1" "2")
  [[ "$mon_choice" == "2" ]] && WITH_MONITORING=true
}

# ── Compose file management ───────────────────────────────

download_compose_files() {
  mkdir -p "$EDDI_DIR"

  local files_to_download=()
  COMPOSE_FILES=()

  # Base compose file depends on database choice
  if [[ "${DB_CHOICE:-1}" == "2" ]]; then
    files_to_download+=("docker-compose.postgres-only.yml")
    COMPOSE_FILES+=("$EDDI_DIR/docker-compose.postgres-only.yml")
  else
    files_to_download+=("docker-compose.yml")
    COMPOSE_FILES+=("$EDDI_DIR/docker-compose.yml")
  fi

  # Auth overlay
  if [[ "$WITH_AUTH" == "true" ]]; then
    files_to_download+=("docker-compose.auth.yml")
    COMPOSE_FILES+=("$EDDI_DIR/docker-compose.auth.yml")
  fi

  # Monitoring overlay
  if [[ "$WITH_MONITORING" == "true" ]]; then
    files_to_download+=("docker-compose.monitoring.yml")
    COMPOSE_FILES+=("$EDDI_DIR/docker-compose.monitoring.yml")
  fi

  # Download each file
  for f in "${files_to_download[@]}"; do
    echo -ne "  Downloading ${f}... "
    if curl -fsSL "${COMPOSE_BASE_URL}/${f}" -o "$EDDI_DIR/$f"; then
      echo -e "${GREEN}✅${RESET}"
    else
      fail "Failed to download ${f}.\n     Check your internet connection."
    fi
  done


  # Save config for eddi CLI wrapper
  echo "COMPOSE_FILES=\"${COMPOSE_FILES[*]}\"" > "$EDDI_DIR/.eddi-config"
  echo "EDDI_PORT=$EDDI_PORT" >> "$EDDI_DIR/.eddi-config"
}

# Helper: run docker compose with the right -f flags
compose_cmd() {
  local flags=()
  for f in "${COMPOSE_FILES[@]}"; do
    flags+=(-f "$f")
  done
  docker compose "${flags[@]}" "$@"
}

# ── Start containers ──────────────────────────────────────

start_eddi() {
  section "Starting EDDI"

  echo "  Pulling images (this may take a minute)..."
  echo ""
  if compose_cmd pull 2>&1 | sed 's/^/    /'; then
    echo ""
    info "Images pulled"
  else
    fail "Failed to pull images. Check internet connection and disk space.\n     Run: docker system df"
  fi

  echo -ne "  Starting containers...   "
  if compose_cmd up -d 2>/dev/null; then
    echo -e "${GREEN}✅${RESET}"
    CONTAINERS_STARTED=true
  else
    fail "Failed to start containers.\n     Check logs: docker compose logs in $EDDI_DIR"
  fi
}

# ── Health check ──────────────────────────────────────────

wait_for_ready() {
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
  HEALTHY=false
  exit 1
}

# ── Import initial bots ──────────────────────────────────

maybe_import_initial_bots() {
  local bot_count
  bot_count=$(detect_deployed_bots) || bot_count=0

  if [[ "$bot_count" -eq 0 ]]; then
    echo -ne "  Deploying Bot Father...  "
    local status
    status=$(curl -sf -o /dev/null -w "%{http_code}" \
      -X POST "http://localhost:${EDDI_PORT}/backup/import/initialBots" 2>/dev/null) || status="000"

    if [[ "$status" == "200" ]]; then
      echo -e "${GREEN}✅${RESET}"
    else
      echo -e "${YELLOW}⚠️${RESET}  ${DIM}(HTTP ${status} — non-fatal, EDDI is still usable)${RESET}"
    fi
  else
    info "Found ${bot_count} deployed bot(s), skipping initial import."
  fi
}

# ── Success banner ────────────────────────────────────────

print_success() {
  echo ""
  echo -e "${GREEN}${BOLD}─── 🎉 Setup Complete! ────────────────────────────${RESET}"
  echo ""
  echo -e "  ${BOLD}Dashboard${RESET}  →  ${CYAN}http://localhost:${EDDI_PORT}${RESET}"
  echo -e "  ${BOLD}MCP${RESET}        →  ${CYAN}http://localhost:${EDDI_PORT}/mcp${RESET}"
  echo -e "  ${BOLD}API docs${RESET}   →  ${CYAN}http://localhost:${EDDI_PORT}/q/swagger-ui${RESET}"

  if [[ "$WITH_AUTH" == "true" ]]; then
    echo -e "  ${BOLD}Keycloak${RESET}   →  ${CYAN}http://localhost:8180${RESET}  ${DIM}(admin/admin)${RESET}"
  fi

  echo ""
  echo -e "  ${BOLD}🤖 Ready to create your first bot?${RESET}"
  echo "     Open the dashboard and chat with Bot Father!"
  echo "     It will guide you through choosing an AI provider,"
  echo "     setting up API keys, and building your first bot."
  echo ""
  echo -e "  ${DIM}┌─ Claude Desktop / Cursor ──────────────────────────┐${RESET}"
  echo -e "  ${DIM}│ Add to your MCP config:                            │${RESET}"
  echo -e "  ${DIM}│   \"eddi\": { \"url\": \"http://localhost:${EDDI_PORT}/mcp\" }   │${RESET}"
  echo -e "  ${DIM}└────────────────────────────────────────────────────┘${RESET}"
  echo ""
  echo -e "  ${BOLD}Manage EDDI:${RESET}"
  echo "    eddi status     health + deployed bots"
  echo "    eddi logs       view container logs"
  echo "    eddi stop       stop all containers"
  echo "    eddi update     pull latest version"
  echo ""
  echo -e "  ${DIM}Install dir: ${EDDI_DIR}${RESET}"
  echo ""

  # Try to open browser automatically
  local url="http://localhost:${EDDI_PORT}"
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

if [[ -f "$CONFIG_FILE" ]]; then
  source "$CONFIG_FILE"
else
  echo "EDDI not installed. Run the install script first."
  exit 1
fi

# Build compose flags
COMPOSE_FLAGS=""
for f in $COMPOSE_FILES; do
  COMPOSE_FLAGS="$COMPOSE_FLAGS -f $f"
done

case "${1:-help}" in
  start)
    docker compose $COMPOSE_FLAGS up -d
    echo "EDDI started on port ${EDDI_PORT}"
    ;;
  stop)
    docker compose $COMPOSE_FLAGS down
    echo "EDDI stopped."
    ;;
  restart)
    docker compose $COMPOSE_FLAGS down
    docker compose $COMPOSE_FLAGS up -d
    echo "EDDI restarted."
    ;;
  status)
    echo "Containers:"
    docker compose $COMPOSE_FLAGS ps
    echo ""
    if curl -sf "http://localhost:${EDDI_PORT}/q/health/ready" &>/dev/null; then
      echo "Health: ✅ ready"
      BOT_COUNT=$(curl -sf "http://localhost:${EDDI_PORT}/administration/unrestricted/deploymentstatus" 2>/dev/null | grep -o '"botId"' | wc -l || echo "0")
      echo "Deployed bots: $BOT_COUNT"
    else
      echo "Health: ❌ not ready"
    fi
    ;;
  logs)
    shift
    docker compose $COMPOSE_FLAGS logs "${@}"
    ;;
  update)
    echo "Pulling latest images..."
    docker compose $COMPOSE_FLAGS pull
    docker compose $COMPOSE_FLAGS up -d
    echo "EDDI updated."
    ;;
  uninstall)
    echo "This will stop EDDI and remove all data."
    read -rp "Are you sure? [y/N]: " confirm
    if [[ "$confirm" =~ ^[Yy]$ ]]; then
      docker compose $COMPOSE_FLAGS down -v
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
    echo "  status      Show health and bot count"
    echo "  logs [-f]   View container logs"
    echo "  update      Pull latest images and restart"
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
  echo -e "  Port:           ${BOLD}${EDDI_PORT}${RESET}"
  echo -e "  Install dir:    ${BOLD}${EDDI_DIR}${RESET}"
}

main() {
  local start_time
  start_time=$(date +%s)

  banner
  check_prerequisites

  if [[ "$EDDI_ALREADY_RUNNING" == "true" ]]; then
    # EDDI is already running — skip infra, check bots
    section "EDDI Already Running"
    maybe_import_initial_bots
    install_cli_wrapper
    print_success
    exit 0
  fi

  # Interactive wizard
  wizard_database
  wizard_auth
  wizard_monitoring

  # Show chosen config
  print_config_summary

  # Download and start
  download_compose_files
  start_eddi
  wait_for_ready
  maybe_import_initial_bots

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
