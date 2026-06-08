#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
#  EDDI — GCP VM Provisioner
#
#  Creates a Google Cloud VM, installs Docker, and runs EDDI via install.sh.
#  Optionally sets up nginx + Let's Encrypt HTTPS with Keycloak auth.
#
#  Prerequisites:
#    - gcloud CLI installed and authenticated  (gcloud auth login)
#    - A GCP project set                       (gcloud config set project <id>)
#      or pass --project=<id> to every command
#
#  COMMANDS
#  ────────
#    create   Provision a new VM and install EDDI
#    delete   Delete a VM (and its static IP if reserved)
#    list     List all EDDI VMs in the project
#    ssh      Open an interactive SSH session to a VM
#    status   Show VM state and EDDI health
#    logs     Stream the EDDI startup log from the VM
#    ip       Print the external IP of a VM
#
#  USAGE EXAMPLES
#  ──────────────
#    # Quick demo (open access, no HTTPS)
#    ./gcp/provision-vm.sh create
#
#    # Keycloak + Let's Encrypt HTTPS  ← recommended for demos
#    ./gcp/provision-vm.sh create --with-auth --https \
#        --letsencrypt-email=you@example.com
#
#    # Production with static IP
#    ./gcp/provision-vm.sh create --name=eddi-prod \
#        --machine-type=e2-standard-4 \
#        --with-auth --https --letsencrypt-email=you@example.com \
#        --static-ip --project=my-gcp-project
#
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

# ── Defaults ─────────────────────────────────────────────────────────────────

DEFAULT_VM_NAME="eddi-demo"
DEFAULT_MACHINE_TYPE="e2-standard-2"   # 2 vCPU, 8 GB RAM
DEFAULT_ZONE="us-central1-a"
DEFAULT_DISK_SIZE="30"                 # GB, SSD boot disk
DEFAULT_IMAGE_FAMILY="ubuntu-2204-lts"
DEFAULT_IMAGE_PROJECT="ubuntu-os-cloud"
DEFAULT_EDDI_PORT="7070"
DEFAULT_EDDI_HTTPS_PORT="7443"
DEFAULT_EDDI_BRANCH="main"
DEFAULT_EDDI_VERSION="latest"
NETWORK_TAG="eddi-server"
FIREWALL_RULE_HTTP="allow-eddi-http"
FIREWALL_RULE_HTTPS="allow-eddi-https"
FIREWALL_RULE_KC="allow-eddi-keycloak"
FIREWALL_RULE_GRAFANA="allow-eddi-grafana"
FIREWALL_RULE_NGINX_HTTP="allow-eddi-nginx-http"
FIREWALL_RULE_NGINX_HTTPS="allow-eddi-nginx-https"

# ── Colors ────────────────────────────────────────────────────────────────────

if [[ -t 1 ]]; then
  BOLD='\033[1m'; GREEN='\033[0;32m'; YELLOW='\033[0;33m'
  RED='\033[0;31m'; CYAN='\033[0;36m'; DIM='\033[2m'; RESET='\033[0m'
else
  BOLD='' GREEN='' YELLOW='' RED='' CYAN='' DIM='' RESET=''
fi

info()    { echo -e "  ${GREEN}✅${RESET}  $1"; }
warn()    { echo -e "  ${YELLOW}⚠️   $1${RESET}"; }
fail()    { echo -e "  ${RED}❌  $1${RESET}" >&2; exit 1; }
step()    { echo -e "\n${BOLD}── $1${RESET}"; echo ""; }
dim()     { echo -e "  ${DIM}$1${RESET}"; }

# ── Help ──────────────────────────────────────────────────────────────────────

usage() {
  cat <<EOF

${BOLD}EDDI GCP VM Provisioner${RESET}

${BOLD}Usage:${RESET}
  $(basename "$0") <command> [options]

${BOLD}Commands:${RESET}
  create          Provision a new GCP VM and install EDDI
  update          Update EDDI image tag on an existing VM
  install-reset   Install the 48-hour demo-reset timer on a VM
  delete          Delete a VM (pass VM_NAME as first positional arg)
  list            List all EDDI VMs in the project
  ssh             SSH into a VM (pass VM_NAME as first positional arg)
  status          Check VM state and EDDI health
  logs            Stream startup/EDDI logs from the VM
  ip              Print the external IP of a VM

${BOLD}Create options:${RESET}
  --name=NAME               VM name                      [${DEFAULT_VM_NAME}]
  --zone=ZONE               GCP zone                     [${DEFAULT_ZONE}]
  --machine-type=TYPE       GCP machine type             [${DEFAULT_MACHINE_TYPE}]
  --disk-size=GB            Boot disk size in GB         [${DEFAULT_DISK_SIZE}]
  --project=PROJECT_ID      GCP project ID               [from gcloud config]
  --eddi-branch=BRANCH      EDDI GitHub branch           [${DEFAULT_EDDI_BRANCH}]
  --eddi-version=TAG        EDDI Docker image tag        [${DEFAULT_EDDI_VERSION}]
  --with-auth               Enable Keycloak OIDC auth
  --with-monitoring         Include Grafana + Prometheus
  --with-postgres           Use PostgreSQL instead of MongoDB
  --open-access             No auth (dev/demo mode, default)
  --vault-key=KEY           Set EDDI vault master key (min 16 chars)
  --https                   Set up nginx + Let's Encrypt HTTPS (implies --with-auth)
  --letsencrypt-email=EMAIL Email for Let's Encrypt notifications
  --static-ip               Reserve a static external IP address
  --no-wait                 Don't wait for EDDI to become healthy

${BOLD}Update options:${RESET}
  --eddi-version=TAG        New image tag to deploy (required)
  --no-wait                 Don't wait for EDDI to become healthy after restart

${BOLD}How HTTPS works:${RESET}
  When --https is passed, the VM installs nginx and requests a Let's Encrypt
  certificate for two sslip.io subdomains derived from the VM's external IP:

    EDDI:      https://<ip-with-dashes>.sslip.io/manage
    Keycloak:  https://auth.<ip-with-dashes>.sslip.io

  sslip.io is a free wildcard DNS service — no domain registration needed.
  e.g. for IP 34.27.206.65:  34-27-206-65.sslip.io

${BOLD}Machine type reference:${RESET}
  e2-medium      2 vCPU,  4 GB   minimum for testing
  e2-standard-2  2 vCPU,  8 GB   recommended for demo   (default)
  e2-standard-4  4 vCPU, 16 GB   recommended for production
  e2-standard-8  8 vCPU, 32 GB   high load / full stack

${BOLD}Examples:${RESET}
  # Quick open-access demo
  $(basename "$0") create

  # Keycloak + HTTPS (recommended for demos you share with others)
  $(basename "$0") create --with-auth --https \\
      --letsencrypt-email=you@example.com

  # Full production stack
  $(basename "$0") create --name=eddi-prod \\
      --machine-type=e2-standard-4 \\
      --with-auth --https --letsencrypt-email=you@example.com \\
      --with-postgres --static-ip \\
      --project=my-gcp-project

  # Update EDDI to a specific version
  $(basename "$0") update eddi-demo --eddi-version=6.0.0-RC2

  # Roll back to latest
  $(basename "$0") update eddi-demo --eddi-version=latest

  # Install 48-hour demo-reset timer
  $(basename "$0") install-reset eddi-demo

  # Delete
  $(basename "$0") delete eddi-demo

  # SSH
  $(basename "$0") ssh eddi-demo

  # Watch install + HTTPS setup log
  $(basename "$0") logs eddi-demo

EOF
  exit 0
}

# ── Prerequisites ─────────────────────────────────────────────────────────────

check_prerequisites() {
  if ! command -v gcloud &>/dev/null; then
    fail "gcloud CLI not found.\n\n     Install: https://cloud.google.com/sdk/docs/install\n     Then:  gcloud auth login && gcloud config set project PROJECT_ID"
  fi
  if ! gcloud auth print-access-token &>/dev/null 2>&1; then
    fail "Not authenticated. Run: gcloud auth login"
  fi
  info "gcloud CLI found ($(gcloud version --format='value(Google Cloud SDK)' 2>/dev/null || echo 'unknown'))"
}

resolve_project() {
  if [[ -z "${GCP_PROJECT:-}" ]]; then
    GCP_PROJECT=$(gcloud config get-value project 2>/dev/null | tr -d '[:space:]') || true
  fi
  if [[ -z "$GCP_PROJECT" ]]; then
    fail "No GCP project set.\n     Run: gcloud config set project YOUR_PROJECT_ID\n     Or:  --project=YOUR_PROJECT_ID"
  fi
  info "Project: ${CYAN}${GCP_PROJECT}${RESET}"
}

# ── Firewall rules ────────────────────────────────────────────────────────────

ensure_firewall_rule() {
  local rule_name="$1"
  local ports="$2"
  local description="$3"

  if gcloud compute firewall-rules describe "$rule_name" \
      --project="$GCP_PROJECT" &>/dev/null 2>&1; then
    dim "Firewall rule '${rule_name}' already exists — skipping."
    return 0
  fi

  echo -ne "  Creating firewall rule '${rule_name}'... "
  local fw_err
  fw_err=$(mktemp /tmp/eddi-fw-err-XXXXXX)
  if gcloud compute firewall-rules create "$rule_name" \
    --project="$GCP_PROJECT" \
    --direction=INGRESS \
    --priority=1000 \
    --network=default \
    --action=ALLOW \
    --rules="tcp:${ports}" \
    --target-tags="$NETWORK_TAG" \
    --description="$description" \
    --quiet >/dev/null 2>"$fw_err"; then
    echo -e "${GREEN}✅${RESET}"
  else
    echo ""
    echo -e "  ${RED}❌  Failed to create firewall rule '${rule_name}':${RESET}"
    sed 's/^/    /' "$fw_err" >&2
    rm -f "$fw_err"
    exit 1
  fi
  rm -f "$fw_err"
}

setup_firewall_rules() {
  step "Firewall Rules"

  # Direct EDDI ports (always open — useful for debugging / MCP direct access)
  ensure_firewall_rule "$FIREWALL_RULE_HTTP" \
    "$EDDI_PORT" "EDDI HTTP API and dashboard (direct)"
  ensure_firewall_rule "$FIREWALL_RULE_HTTPS" \
    "$EDDI_HTTPS_PORT" "EDDI HTTPS (direct)"

  if [[ "$WITH_AUTH" == "true" ]]; then
    ensure_firewall_rule "$FIREWALL_RULE_KC" \
      "8180" "EDDI Keycloak authentication portal (direct)"
  fi

  if [[ "$WITH_MONITORING" == "true" ]]; then
    ensure_firewall_rule "$FIREWALL_RULE_GRAFANA" \
      "3000,9090" "EDDI Grafana + Prometheus"
  fi

  # nginx ports — needed for Let's Encrypt HTTP-01 challenge and HTTPS traffic
  if [[ "$SETUP_HTTPS" == "true" ]]; then
    ensure_firewall_rule "$FIREWALL_RULE_NGINX_HTTP" \
      "80" "nginx HTTP (Let's Encrypt ACME challenge + HTTP→HTTPS redirect)"
    ensure_firewall_rule "$FIREWALL_RULE_NGINX_HTTPS" \
      "443" "nginx HTTPS reverse proxy (EDDI + Keycloak)"
  fi
}

# ── Static IP ─────────────────────────────────────────────────────────────────

maybe_reserve_static_ip() {
  [[ "$STATIC_IP" != "true" ]] && return 0

  local address_name="${VM_NAME}-ip"
  local region="${ZONE%-*}"

  step "Static IP"

  if gcloud compute addresses describe "$address_name" \
      --region="$region" --project="$GCP_PROJECT" &>/dev/null 2>&1; then
    dim "Static IP '${address_name}' already exists — reusing."
    RESERVED_IP=$(gcloud compute addresses describe "$address_name" \
      --region="$region" --project="$GCP_PROJECT" \
      --format='value(address)' 2>/dev/null)
    info "Reserved IP: ${CYAN}${RESERVED_IP}${RESET}"
    return 0
  fi

  echo -ne "  Reserving static IP '${address_name}'... "
  gcloud compute addresses create "$address_name" \
    --region="$region" --project="$GCP_PROJECT" --quiet >/dev/null 2>&1
  echo -e "${GREEN}✅${RESET}"

  RESERVED_IP=$(gcloud compute addresses describe "$address_name" \
    --region="$region" --project="$GCP_PROJECT" \
    --format='value(address)' 2>/dev/null)
  info "Reserved IP: ${CYAN}${RESERVED_IP}${RESET}"
}

# ── Startup script ────────────────────────────────────────────────────────────
#
# All variables from this script that should be evaluated at provision time
# (e.g. ${EDDI_BRANCH}) are written unescaped.
# Variables that should be evaluated at runtime on the VM (e.g. ${EXTERNAL_IP})
# are written as \${VAR} so they survive into the startup script file.
#
# For the nginx config template we use placeholder tokens (e.g. __EDDI_DOMAIN__)
# and substitute them at runtime with sed — this avoids conflicts between bash
# ${VAR} syntax and nginx $var syntax inside heredocs.

build_startup_script() {
  local install_flags="--defaults"
  if [[ "$WITH_AUTH" == "true" ]];       then install_flags+=" --with-auth"; fi
  if [[ "$WITH_MONITORING" == "true" ]]; then install_flags+=" --with-monitoring"; fi
  if [[ "$WITH_POSTGRES" == "true" ]];   then install_flags+=" --db=postgres"; fi
  if [[ -n "${VAULT_KEY_ARG:-}" ]];      then install_flags+=" --vault-key=${VAULT_KEY_ARG}"; fi

  # Capture provision-time values into locals so the heredoc picks them up cleanly
  local p_branch="${EDDI_BRANCH}"
  local p_version="${EDDI_VERSION}"
  local p_port="${EDDI_PORT}"
  local p_https_port="${EDDI_HTTPS_PORT}"
  local p_flags="${install_flags}"
  local p_setup_https="${SETUP_HTTPS}"
  local p_le_email="${LETSENCRYPT_EMAIL:-admin@example.com}"

  cat <<STARTUP_SCRIPT
#!/usr/bin/env bash
# EDDI VM startup script — auto-executed by GCP on first boot
set -euo pipefail

LOGFILE="/var/log/eddi-install.log"
exec > >(tee -a "\$LOGFILE") 2>&1

echo "=== EDDI startup script starting at \$(date) ==="

export DEBIAN_FRONTEND=noninteractive
export HOME=/root
export USER=root

# ── [1/6] System packages ──────────────────────────────────────────────────────
echo "[1/6] Updating system packages..."
apt-get update -qq
apt-get upgrade -y -qq
apt-get install -y -qq \\
  curl jq ca-certificates gnupg lsb-release \\
  nginx certbot python3-certbot-nginx

# ── [2/6] Docker ───────────────────────────────────────────────────────────────
echo "[2/6] Installing Docker..."
if ! command -v docker &>/dev/null; then
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg \\
    -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  echo "deb [arch=\$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \\
    https://download.docker.com/linux/ubuntu \\
    \$(. /etc/os-release && echo "\$VERSION_CODENAME") stable" \\
    > /etc/apt/sources.list.d/docker.list
  apt-get update -qq
  apt-get install -y -qq \\
    docker-ce docker-ce-cli containerd.io \\
    docker-buildx-plugin docker-compose-plugin
  systemctl enable docker
  systemctl start docker
  echo "Docker installed: \$(docker --version)"
else
  echo "Docker already installed: \$(docker --version)"
fi
# Remove stale Docker config that may cause "permission denied" on pull
systemctl start docker 2>/dev/null || true
rm -rf /root/.docker

# ── [3/6] External IP + sslip.io domains ───────────────────────────────────────
echo "[3/6] Detecting external IP..."
EXTERNAL_IP=\$(curl -sf \\
  "http://metadata.google.internal/computeMetadata/v1/instance/network-interfaces/0/access-configs/0/external-ip" \\
  -H "Metadata-Flavor: Google" || echo "")
echo "External IP: \${EXTERNAL_IP}"

HTTPS_ENABLED=false
EDDI_DOMAIN=""
KC_DOMAIN=""

if [[ "${p_setup_https}" == "true" && -n "\${EXTERNAL_IP}" ]]; then
  IP_DASHES="\${EXTERNAL_IP//./-}"
  EDDI_DOMAIN="\${IP_DASHES}.sslip.io"
  KC_DOMAIN="auth.\${IP_DASHES}.sslip.io"
  echo "EDDI domain:     \${EDDI_DOMAIN}"
  echo "Keycloak domain: \${KC_DOMAIN}"
fi

# ── [4/6] Let's Encrypt certificate ────────────────────────────────────────────
if [[ "${p_setup_https}" == "true" && -n "\${EDDI_DOMAIN}" ]]; then
  echo "[4/6] Requesting Let's Encrypt certificate..."

  mkdir -p /var/www/certbot
  rm -f /etc/nginx/sites-enabled/default

  # Minimal nginx config just to serve the ACME HTTP-01 challenge
  cat > /etc/nginx/sites-available/eddi-acme <<'ACME_NGINX'
server {
    listen 80 default_server;
    server_name _;
    location /.well-known/acme-challenge/ {
        root /var/www/certbot;
    }
    location / {
        return 200 "EDDI is being configured, please wait...";
        add_header Content-Type text/plain;
    }
}
ACME_NGINX

  ln -sf /etc/nginx/sites-available/eddi-acme /etc/nginx/sites-enabled/eddi-acme
  systemctl enable nginx
  nginx -t && systemctl restart nginx

  if certbot certonly \\
      --webroot \\
      --webroot-path /var/www/certbot \\
      --non-interactive \\
      --agree-tos \\
      -m "${p_le_email}" \\
      -d "\${EDDI_DOMAIN}" \\
      -d "\${KC_DOMAIN}" 2>&1; then
    HTTPS_ENABLED=true
    echo "Certificate obtained for \${EDDI_DOMAIN} and \${KC_DOMAIN}"
  else
    echo "WARNING: Let's Encrypt cert request failed — will continue without HTTPS."
    echo "         Check: certbot certificates"
  fi
else
  echo "[4/6] Skipping Let's Encrypt (HTTPS not requested)."
fi

# ── [5/6] EDDI install ─────────────────────────────────────────────────────────
echo "[5/6] Installing EDDI (branch: ${p_branch}, version: ${p_version}, flags: ${p_flags})..."
export EDDI_BRANCH="${p_branch}"
export EDDI_VERSION="${p_version}"
export EDDI_PORT="${p_port}"
export EDDI_HTTPS_PORT="${p_https_port}"

curl -fsSL "https://raw.githubusercontent.com/labsai/EDDI/\${EDDI_BRANCH}/install.sh" \\
  | bash -s -- ${p_flags}

# ── [6/6] HTTPS reverse proxy + Keycloak HTTPS config ─────────────────────────
if [[ "\${HTTPS_ENABLED}" == "true" ]]; then
  echo "[6/6] Configuring nginx HTTPS reverse proxy..."

  CERT_DIR="/etc/letsencrypt/live/\${EDDI_DOMAIN}"

  # nginx config template — uses placeholder tokens to avoid clashing with
  # nginx's own \$variable syntax which must be preserved literally.
  cat > /tmp/nginx-eddi.tmpl <<'NGINX_TMPL'
server {
    listen 80;
    server_name __EDDI_DOMAIN__ __KC_DOMAIN__;
    return 301 https://\$host\$request_uri;
}

server {
    listen 443 ssl http2;
    server_name __EDDI_DOMAIN__;

    ssl_certificate     __CERT_DIR__/fullchain.pem;
    ssl_certificate_key __CERT_DIR__/privkey.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_prefer_server_ciphers off;
    ssl_session_cache   shared:SSL:10m;
    ssl_session_timeout 1d;

    client_max_body_size 50m;

    location / {
        proxy_pass         http://localhost:__EDDI_PORT__;
        proxy_http_version 1.1;
        proxy_set_header   Upgrade \$http_upgrade;
        proxy_set_header   Connection "upgrade";
        proxy_set_header   Host \$host;
        proxy_set_header   X-Real-IP \$remote_addr;
        proxy_set_header   X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto https;
        proxy_read_timeout 300;
        proxy_send_timeout 300;
        proxy_buffer_size  128k;
        proxy_buffers      4 256k;
    }
}

server {
    listen 443 ssl http2;
    server_name __KC_DOMAIN__;

    ssl_certificate     __CERT_DIR__/fullchain.pem;
    ssl_certificate_key __CERT_DIR__/privkey.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_prefer_server_ciphers off;
    ssl_session_cache   shared:SSL:10m;
    ssl_session_timeout 1d;

    location / {
        proxy_pass         http://localhost:8180;
        proxy_http_version 1.1;
        proxy_set_header   Host \$host;
        proxy_set_header   X-Real-IP \$remote_addr;
        proxy_set_header   X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto https;
        proxy_buffer_size  128k;
        proxy_buffers      4 256k;
    }
}
NGINX_TMPL

  # Substitute placeholders with runtime values
  sed \\
    -e "s|__EDDI_DOMAIN__|\${EDDI_DOMAIN}|g" \\
    -e "s|__KC_DOMAIN__|\${KC_DOMAIN}|g" \\
    -e "s|__CERT_DIR__|\${CERT_DIR}|g" \\
    -e "s|__EDDI_PORT__|${p_port}|g" \\
    /tmp/nginx-eddi.tmpl > /etc/nginx/sites-available/eddi

  rm -f /etc/nginx/sites-enabled/eddi-acme
  ln -sf /etc/nginx/sites-available/eddi /etc/nginx/sites-enabled/eddi
  nginx -t && systemctl reload nginx
  echo "nginx configured for HTTPS."

  # ── docker-compose override: point Keycloak URLs to HTTPS domains ─────────────
  EDDI_DIR="/root/.eddi"
  EDDI_CONFIG="\${EDDI_DIR}/.eddi-config"

  cat > "\${EDDI_DIR}/docker-compose.override.yml" <<OVERRIDE_EOF
services:
  eddi:
    environment:
      EDDI_KEYCLOAK_PUBLIC_URL: "https://\${KC_DOMAIN}"
      QUARKUS_OIDC_TOKEN_ISSUER: "https://\${KC_DOMAIN}/realms/eddi"
      QUARKUS_HTTP_CORS_ORIGINS: "https://\${EDDI_DOMAIN},http://localhost:${p_port}"
  keycloak:
    environment:
      KC_HOSTNAME: "https://\${KC_DOMAIN}"
OVERRIDE_EOF

  # Register the override in the eddi CLI config so "eddi restart" uses it too
  if ! grep -q "docker-compose.override.yml" "\${EDDI_CONFIG}" 2>/dev/null; then
    COMPOSE_FILES_CURRENT=\$(grep "^COMPOSE_FILES=" "\${EDDI_CONFIG}" | cut -d= -f2-)
    sed -i "s|^COMPOSE_FILES=.*|COMPOSE_FILES=\${COMPOSE_FILES_CURRENT} \${EDDI_DIR}/docker-compose.override.yml|" "\${EDDI_CONFIG}"
  fi

  # Restart containers to apply the HTTPS override
  echo "Restarting containers with HTTPS config..."
  mapfile -t COMPOSE_ARGS < <(grep "^COMPOSE_FILES=" "\${EDDI_CONFIG}" | cut -d= -f2- | tr ' ' '\n' | grep -v '^$' | sed 's/^/-f /' )
  # shellcheck disable=SC2086
  docker compose --env-file "\${EDDI_DIR}/.env" \${COMPOSE_ARGS[*]} down
  docker compose --env-file "\${EDDI_DIR}/.env" \${COMPOSE_ARGS[*]} up -d

  # Wait for EDDI to be healthy after restart
  echo "Waiting for EDDI to restart..."
  for i in \$(seq 1 60); do
    if curl -sf --connect-timeout 3 "http://localhost:${p_port}/q/health/ready" &>/dev/null; then
      echo "EDDI is healthy after restart."
      break
    fi
    sleep 5
  done

  # Import Agent Father (first install.sh may have raced; retry here)
  curl -sf -X POST "http://localhost:${p_port}/backup/import/initialAgents" &>/dev/null || true

  # ── Update Keycloak client with HTTPS redirect URIs ───────────────────────────
  echo "Updating Keycloak eddi-frontend client..."
  KC_TOKEN=""
  for attempt in \$(seq 1 18); do
    KC_TOKEN=\$(curl -sf -X POST \\
      "http://localhost:8180/realms/master/protocol/openid-connect/token" \\
      -d "client_id=admin-cli&username=admin&password=admin&grant_type=password" \\
      2>/dev/null | jq -r '.access_token // empty' 2>/dev/null) || KC_TOKEN=""
    [[ -n "\${KC_TOKEN:-}" ]] && break
    echo "  Waiting for Keycloak admin API... (\${attempt}/18)"
    sleep 10
  done

  if [[ -n "\${KC_TOKEN:-}" ]]; then
    KC_CLIENT_UUID=\$(curl -sf \\
      -H "Authorization: Bearer \${KC_TOKEN}" \\
      "http://localhost:8180/admin/realms/eddi/clients?clientId=eddi-frontend" \\
      2>/dev/null | jq -r '.[0].id // empty') || KC_CLIENT_UUID=""

    if [[ -n "\${KC_CLIENT_UUID:-}" ]]; then
      EXISTING_CLIENT=\$(curl -sf \\
        -H "Authorization: Bearer \${KC_TOKEN}" \\
        "http://localhost:8180/admin/realms/eddi/clients/\${KC_CLIENT_UUID}" 2>/dev/null)
      UPDATED_CLIENT=\$(echo "\${EXISTING_CLIENT}" | jq \\
        --arg d "https://\${EDDI_DOMAIN}" \\
        '.redirectUris += [\$d + "/*"] | .webOrigins += [\$d]' 2>/dev/null) || UPDATED_CLIENT=""

      if [[ -n "\${UPDATED_CLIENT:-}" ]]; then
        STATUS=\$(curl -sf -o /dev/null -w "%{http_code}" -X PUT \\
          -H "Authorization: Bearer \${KC_TOKEN}" \\
          -H "Content-Type: application/json" \\
          "http://localhost:8180/admin/realms/eddi/clients/\${KC_CLIENT_UUID}" \\
          -d "\${UPDATED_CLIENT}" 2>/dev/null) || STATUS="000"
        if [[ "\${STATUS}" == "204" ]]; then
          echo "Keycloak client updated with HTTPS origins."
        else
          echo "WARNING: Keycloak client update returned HTTP \${STATUS}"
        fi
      fi
    fi
  else
    echo "WARNING: Could not reach Keycloak admin API. CORS may need manual update."
  fi

  # Set up certbot auto-renewal
  echo "0 12 * * * root certbot renew --quiet --deploy-hook 'systemctl reload nginx'" \\
    > /etc/cron.d/certbot-renew

  echo ""
  echo "=== HTTPS Setup Complete ==="
  echo "  EDDI:      https://\${EDDI_DOMAIN}/manage"
  echo "  Keycloak:  https://\${KC_DOMAIN}"
  echo "  API Docs:  https://\${EDDI_DOMAIN}/q/swagger-ui"
  echo "  MCP:       https://\${EDDI_DOMAIN}/mcp"
else
  echo "[6/6] Skipping HTTPS config (not requested or cert failed)."
fi

echo "=== Install complete at \$(date) ==="
STARTUP_SCRIPT
}

# ── Create VM ─────────────────────────────────────────────────────────────────

create_vm() {
  step "Creating VM: ${VM_NAME}"

  if gcloud compute instances describe "$VM_NAME" \
      --zone="$ZONE" --project="$GCP_PROJECT" &>/dev/null 2>&1; then
    warn "VM '${VM_NAME}' already exists in zone '${ZONE}'."
    EXTERNAL_IP=$(gcloud compute instances describe "$VM_NAME" \
      --zone="$ZONE" --project="$GCP_PROJECT" \
      --format='value(networkInterfaces[0].accessConfigs[0].natIP)' 2>/dev/null)
    info "Existing VM external IP: ${CYAN}${EXTERNAL_IP}${RESET}"
    return 0
  fi

  local startup_script_file
  startup_script_file=$(mktemp /tmp/eddi-startup-XXXXXX)
  build_startup_script > "$startup_script_file"
  chmod 644 "$startup_script_file"

  local create_args=(
    compute instances create "$VM_NAME"
    --project="$GCP_PROJECT"
    --zone="$ZONE"
    --machine-type="$MACHINE_TYPE"
    --image-family="$IMAGE_FAMILY"
    --image-project="$IMAGE_PROJECT"
    --boot-disk-size="${DISK_SIZE}GB"
    --boot-disk-type="pd-ssd"
    --tags="$NETWORK_TAG"
    --metadata-from-file="startup-script=${startup_script_file}"
    --scopes="default"
  )

  if [[ -n "${RESERVED_IP:-}" ]]; then
    create_args+=(--address="$RESERVED_IP")
  fi

  echo -ne "  Provisioning VM (this takes ~30 seconds)... "
  local gcloud_err
  gcloud_err=$(mktemp /tmp/eddi-gcloud-err-XXXXXX)

  if gcloud "${create_args[@]}" >/dev/null 2>"$gcloud_err"; then
    echo -e "${GREEN}✅${RESET}"
  else
    echo ""
    echo -e "  ${RED}❌  gcloud returned an error:${RESET}"
    echo ""
    sed 's/^/    /' "$gcloud_err" >&2
    rm -f "$gcloud_err" "$startup_script_file"
    exit 1
  fi

  rm -f "$gcloud_err" "$startup_script_file"

  EXTERNAL_IP=$(gcloud compute instances describe "$VM_NAME" \
    --zone="$ZONE" --project="$GCP_PROJECT" \
    --format='value(networkInterfaces[0].accessConfigs[0].natIP)' 2>/dev/null)

  info "VM created: ${CYAN}${VM_NAME}${RESET}"
  info "External IP: ${CYAN}${EXTERNAL_IP}${RESET}"
  info "Zone: ${CYAN}${ZONE}${RESET}"
}

# ── Wait for EDDI health ──────────────────────────────────────────────────────

wait_for_eddi() {
  [[ "$NO_WAIT" == "true" ]] && return 0

  step "Waiting for EDDI to become healthy"

  # With Keycloak, startup takes longer: image pull + KC init + EDDI boot.
  # With HTTPS, there's an additional nginx + certbot + restart cycle after EDDI.
  # Poll the direct HTTP port (7070) — always available even when HTTPS is on.
  local max_wait=720
  local elapsed=0
  local health_url="http://${EXTERNAL_IP}:${EDDI_PORT}/q/health/ready"
  local note="~3-5 min"
  [[ "$WITH_AUTH" == "true" ]] && note="~5-8 min (Keycloak adds startup time)"

  echo "  Polling: ${DIM}${health_url}${RESET}"
  echo "  (VM is booting, installing Docker + pulling images — ${note})"
  if [[ "$SETUP_HTTPS" == "true" ]]; then
    echo "  (HTTPS setup will continue in background after EDDI is healthy)"
  fi
  echo ""
  echo -ne "  "

  while [[ $elapsed -lt $max_wait ]]; do
    if curl -sf --connect-timeout 5 "$health_url" &>/dev/null 2>&1; then
      echo ""
      echo ""
      info "EDDI is healthy! (ready in ${elapsed}s)"
      if [[ "$SETUP_HTTPS" == "true" ]]; then
        echo ""
        echo -e "  ${DIM}nginx + Let's Encrypt cert setup is still running on the VM.${RESET}"
        echo -e "  ${DIM}HTTPS URLs will be ready in ~2-3 more minutes.${RESET}"
        echo -e "  ${DIM}Watch progress:  $(basename "$0") logs ${VM_NAME}${RESET}"
      fi
      return 0
    fi
    sleep 10
    elapsed=$((elapsed + 10))
    echo -ne "."
    if (( elapsed % 60 == 0 )); then
      echo -ne "\n  "
    fi
  done

  echo ""
  echo ""
  warn "EDDI didn't respond within ${max_wait}s."
  echo ""
  echo "  The VM is still running — EDDI or Keycloak may still be starting."
  echo "  Check the install log:"
  echo ""
  echo "    $(basename "$0") logs ${VM_NAME}"
}

# ── Success banner ─────────────────────────────────────────────────────────────

print_success() {
  local ip_dashes="${EXTERNAL_IP//./-}"
  local eddi_domain="${ip_dashes}.sslip.io"
  local kc_domain="auth.${ip_dashes}.sslip.io"

  echo ""
  echo -e "${GREEN}${BOLD}─── Setup Complete! ──────────────────────────────────────────────${RESET}"
  echo ""
  echo -e "  ${BOLD}VM Name${RESET}      ${VM_NAME}"
  echo -e "  ${BOLD}External IP${RESET}  ${CYAN}${EXTERNAL_IP}${RESET}"
  echo -e "  ${BOLD}Zone${RESET}         ${ZONE}"
  echo ""

  if [[ "$SETUP_HTTPS" == "true" ]]; then
    echo -e "  ${BOLD}${GREEN}HTTPS URLs${RESET} (available once cert setup completes ~2-3 min):"
    echo -e "  ${BOLD}Dashboard${RESET}    ${CYAN}https://${eddi_domain}/manage${RESET}"
    echo -e "  ${BOLD}API Docs${RESET}     ${CYAN}https://${eddi_domain}/q/swagger-ui${RESET}"
    echo -e "  ${BOLD}MCP${RESET}          ${CYAN}https://${eddi_domain}/mcp${RESET}"
    echo -e "  ${BOLD}Keycloak${RESET}     ${CYAN}https://${kc_domain}${RESET}"
    echo ""
    echo -e "  ${DIM}Direct HTTP (always available):${RESET}"
    echo -e "  ${DIM}  http://${EXTERNAL_IP}:${EDDI_PORT}/manage${RESET}"
  else
    echo -e "  ${BOLD}Dashboard${RESET}    ${CYAN}http://${EXTERNAL_IP}:${EDDI_PORT}/manage${RESET}"
    echo -e "  ${BOLD}API Docs${RESET}     ${CYAN}http://${EXTERNAL_IP}:${EDDI_PORT}/q/swagger-ui${RESET}"
    echo -e "  ${BOLD}MCP${RESET}          ${CYAN}http://${EXTERNAL_IP}:${EDDI_PORT}/mcp${RESET}"
  fi

  if [[ "$WITH_AUTH" == "true" && "$SETUP_HTTPS" != "true" ]]; then
    echo ""
    echo -e "  ${BOLD}Keycloak${RESET}     ${CYAN}http://${EXTERNAL_IP}:8180${RESET}"
  fi

  if [[ "$WITH_AUTH" == "true" ]]; then
    echo ""
    echo -e "  ${DIM}Login:  eddi / eddi  (admin)  •  viewer / viewer  (read-only)${RESET}"
    echo -e "  ${DIM}Keycloak console admin: admin / admin${RESET}"
  fi

  if [[ "$WITH_MONITORING" == "true" ]]; then
    echo ""
    echo -e "  ${BOLD}Grafana${RESET}      ${CYAN}http://${EXTERNAL_IP}:3000${RESET}  ${DIM}(admin/admin)${RESET}"
    echo -e "  ${BOLD}Prometheus${RESET}   ${CYAN}http://${EXTERNAL_IP}:9090${RESET}"
  fi

  echo ""
  echo -e "  ${BOLD}Manage this VM:${RESET}"
  echo "    $(basename "$0") status ${VM_NAME}   — health + agent count"
  echo "    $(basename "$0") logs   ${VM_NAME}   — stream install/EDDI logs"
  echo "    $(basename "$0") ssh    ${VM_NAME}   — interactive SSH session"
  echo "    $(basename "$0") delete ${VM_NAME}   — destroy VM"
  echo ""

  local mcp_url="http://${EXTERNAL_IP}:${EDDI_PORT}/mcp"
  [[ "$SETUP_HTTPS" == "true" ]] && mcp_url="https://${eddi_domain}/mcp"
  echo -e "  ${DIM}MCP config for Claude Desktop / Cursor:${RESET}"
  echo -e "  ${DIM}  \"eddi\": { \"url\": \"${mcp_url}\" }${RESET}"
  echo ""
}

# ── Config summary ─────────────────────────────────────────────────────────────

print_config_summary() {
  local db_label="MongoDB"
  [[ "$WITH_POSTGRES" == "true" ]] && db_label="PostgreSQL"

  local auth_label="Open access (no login)"
  [[ "$WITH_AUTH" == "true" ]] && auth_label="Keycloak OIDC"

  local mon_label="None"
  [[ "$WITH_MONITORING" == "true" ]] && mon_label="Grafana + Prometheus"

  local https_label="No"
  if [[ "$SETUP_HTTPS" == "true" ]]; then
    local ip_dashes="${EXTERNAL_IP//./-}"
    https_label="Yes — sslip.io (cert requested on boot)"
  fi

  step "Configuration"
  echo -e "  VM name:        ${BOLD}${VM_NAME}${RESET}"
  echo -e "  Zone:           ${BOLD}${ZONE}${RESET}"
  echo -e "  Machine type:   ${BOLD}${MACHINE_TYPE}${RESET}"
  echo -e "  Disk:           ${BOLD}${DISK_SIZE} GB SSD${RESET}"
  echo -e "  Image:          ${BOLD}${IMAGE_FAMILY} / ${IMAGE_PROJECT}${RESET}"
  echo -e "  EDDI branch:    ${BOLD}${EDDI_BRANCH}${RESET}"
  echo -e "  EDDI version:   ${BOLD}labsai/eddi:${EDDI_VERSION}${RESET}"
  echo -e "  Database:       ${BOLD}${db_label}${RESET}"
  echo -e "  Auth:           ${BOLD}${auth_label}${RESET}"
  echo -e "  HTTPS:          ${BOLD}${https_label}${RESET}"
  echo -e "  Monitoring:     ${BOLD}${mon_label}${RESET}"
  echo -e "  Static IP:      ${BOLD}${STATIC_IP}${RESET}"
  echo -e "  Project:        ${BOLD}${GCP_PROJECT}${RESET}"
  echo ""
}

# ── Command: create ───────────────────────────────────────────────────────────

cmd_create() {
  check_prerequisites
  resolve_project
  print_config_summary
  maybe_reserve_static_ip
  setup_firewall_rules
  create_vm
  wait_for_eddi
  print_success
}

# ── Command: delete ───────────────────────────────────────────────────────────

cmd_delete() {
  local name="${1:-$VM_NAME}"
  check_prerequisites
  resolve_project

  step "Deleting VM: ${name}"

  if ! gcloud compute instances describe "$name" \
      --zone="$ZONE" --project="$GCP_PROJECT" &>/dev/null 2>&1; then
    warn "VM '${name}' not found in zone '${ZONE}'. Nothing to delete."
    return 0
  fi

  echo -ne "  Deleting VM '${name}'... "
  gcloud compute instances delete "$name" \
    --zone="$ZONE" --project="$GCP_PROJECT" --quiet >/dev/null 2>&1
  echo -e "${GREEN}✅${RESET}"

  if [[ "$STATIC_IP" == "true" ]]; then
    local address_name="${name}-ip"
    local region="${ZONE%-*}"
    if gcloud compute addresses describe "$address_name" \
        --region="$region" --project="$GCP_PROJECT" &>/dev/null 2>&1; then
      echo -ne "  Releasing static IP '${address_name}'... "
      gcloud compute addresses delete "$address_name" \
        --region="$region" --project="$GCP_PROJECT" --quiet >/dev/null 2>&1
      echo -e "${GREEN}✅${RESET}"
    fi
  fi

  info "VM '${name}' deleted."
  echo ""
  echo -e "  ${DIM}Firewall rules were not deleted (shared across VMs).${RESET}"
  echo -e "  ${DIM}To remove them: gcloud compute firewall-rules delete allow-eddi-* --project=${GCP_PROJECT}${RESET}"
  echo ""
}

# ── Command: update ───────────────────────────────────────────────────────────
#
# SSHes into an existing VM and hot-swaps the EDDI image tag by:
#   1. Writing EDDI_VERSION=<tag> into ~/.eddi/.env on the VM
#   2. docker compose pull eddi   (only the EDDI container — DB/KC untouched)
#   3. docker compose up -d --no-deps eddi

cmd_update() {
  local name="${1:-$VM_NAME}"
  check_prerequisites
  resolve_project

  if [[ "$EDDI_VERSION" == "$DEFAULT_EDDI_VERSION" ]]; then
    fail "No version specified.\n     Usage: $(basename "$0") update ${name} --eddi-version=<tag>\n     Example: $(basename "$0") update ${name} --eddi-version=6.0.0-RC2\n     Use 'latest' to switch back to the rolling latest image."
  fi

  step "Updating EDDI on: ${name}"

  local vm_status
  vm_status=$(gcloud compute instances describe "$name" \
    --zone="$ZONE" --project="$GCP_PROJECT" \
    --format='value(status)' 2>/dev/null) || {
    fail "VM '${name}' not found in zone '${ZONE}'."
  }
  if [[ "$vm_status" != "RUNNING" ]]; then
    fail "VM '${name}' is not running (status: ${vm_status}).\n     Start it: gcloud compute instances start ${name} --zone=${ZONE}"
  fi

  local external_ip
  external_ip=$(gcloud compute instances describe "$name" \
    --zone="$ZONE" --project="$GCP_PROJECT" \
    --format='value(networkInterfaces[0].accessConfigs[0].natIP)' 2>/dev/null)

  info "Target image: ${CYAN}labsai/eddi:${EDDI_VERSION}${RESET}"
  info "VM:           ${CYAN}${name}${RESET} (${external_ip})"
  echo ""

  local version="${EDDI_VERSION}"

  # Run update script remotely via SSH.
  # Variables prefixed \$ are evaluated on the VM; ${version} is expanded locally.
  gcloud compute ssh "$name" \
    --zone="$ZONE" --project="$GCP_PROJECT" \
    -- "sudo bash -s" << REMOTE_SCRIPT
set -euo pipefail
EDDI_DIR=/root/.eddi
ENV_FILE="\${EDDI_DIR}/.env"
CONFIG_FILE="\${EDDI_DIR}/.eddi-config"

if [[ ! -f "\${ENV_FILE}" ]]; then
  echo "ERROR: \${ENV_FILE} not found — is EDDI installed on this VM?"
  exit 1
fi

echo "Setting EDDI_VERSION=${version} in \${ENV_FILE} ..."
if grep -q '^EDDI_VERSION=' "\${ENV_FILE}" 2>/dev/null; then
  sed -i "s|^EDDI_VERSION=.*|EDDI_VERSION=${version}|" "\${ENV_FILE}"
else
  echo "EDDI_VERSION=${version}" >> "\${ENV_FILE}"
fi

COMPOSE_FILES_STR=\$(grep '^COMPOSE_FILES=' "\${CONFIG_FILE}" 2>/dev/null | cut -d= -f2-) \
  || COMPOSE_FILES_STR="\${EDDI_DIR}/docker-compose.yml"
read -ra CF_ARRAY <<< "\${COMPOSE_FILES_STR}"

COMPOSE_ARGS=(--env-file "\${ENV_FILE}")
for f in "\${CF_ARRAY[@]}"; do
  [[ -n "\${f}" ]] && COMPOSE_ARGS+=(-f "\${f}")
done

echo "Pulling labsai/eddi:${version} ..."
docker compose "\${COMPOSE_ARGS[@]}" pull eddi

echo "Restarting EDDI container ..."
docker compose "\${COMPOSE_ARGS[@]}" up -d --no-deps eddi

echo "Remote update complete."
REMOTE_SCRIPT

  echo ""
  info "Image pulled and EDDI restarted."

  if [[ "$NO_WAIT" == "true" ]]; then
    return 0
  fi

  echo ""
  echo "  Waiting for EDDI to become healthy..."
  local health_url="http://${external_ip}:${EDDI_PORT}/q/health/ready"
  local elapsed=0
  echo -ne "  "
  while [[ $elapsed -lt 120 ]]; do
    if curl -sf --connect-timeout 5 "$health_url" &>/dev/null 2>&1; then
      echo ""
      echo ""
      info "EDDI is healthy! (${elapsed}s)"
      echo ""
      echo -e "  ${BOLD}Dashboard${RESET}  →  ${CYAN}http://${external_ip}:${EDDI_PORT}/manage${RESET}"
      echo ""
      return 0
    fi
    sleep 5
    elapsed=$((elapsed + 5))
    echo -ne "."
  done
  echo ""
  warn "EDDI didn't respond within 120s."
  echo "  Check logs: $(basename "$0") logs ${name}"
}

# ── Command: install-reset ────────────────────────────────────────────────────
#
# Copies eddi-demo-reset.sh to the VM and installs a systemd timer that fires
# strictly every 48 hours.  Idempotent — safe to re-run.

cmd_install_reset() {
  local name="${1:-$VM_NAME}"
  check_prerequisites
  resolve_project

  step "Installing demo-reset timer on: ${name}"

  local vm_status
  vm_status=$(gcloud compute instances describe "$name" \
    --zone="$ZONE" --project="$GCP_PROJECT" \
    --format='value(status)' 2>/dev/null) || {
    fail "VM '${name}' not found in zone '${ZONE}'."
  }
  if [[ "$vm_status" != "RUNNING" ]]; then
    fail "VM '${name}' is not running (status: ${vm_status})."
  fi

  local script_dir
  script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  local reset_script="${script_dir}/eddi-demo-reset.sh"
  [[ -f "$reset_script" ]] || fail "eddi-demo-reset.sh not found at: ${reset_script}"

  info "Uploading eddi-demo-reset.sh..."
  gcloud compute scp "$reset_script" "${name}:/tmp/eddi-demo-reset.sh" \
    --zone="$ZONE" --project="$GCP_PROJECT" >/dev/null

  info "Installing script and systemd timer on VM..."
  gcloud compute ssh "$name" \
    --zone="$ZONE" --project="$GCP_PROJECT" \
    -- "sudo bash -s" << 'REMOTE_SCRIPT'
set -euo pipefail
EDDI_DIR=/root/.eddi
DEST="${EDDI_DIR}/eddi-demo-reset.sh"

install -m 0755 /tmp/eddi-demo-reset.sh "$DEST"
rm -f /tmp/eddi-demo-reset.sh

# Remove any legacy cron file from a previous install
rm -f /etc/cron.d/eddi-demo-reset

# systemd service unit — runs the reset script, appends to log
cat > /etc/systemd/system/eddi-reset.service << UNIT
[Unit]
Description=EDDI demo reset — clear conversation data
After=docker.service

[Service]
Type=oneshot
ExecStart=${DEST}
StandardOutput=append:/var/log/eddi-reset.log
StandardError=append:/var/log/eddi-reset.log
UNIT

# systemd timer unit — fires strictly every 48 h after the last run
# OnBootSec delays the first run by 10 min after a fresh reboot so
# EDDI has time to finish starting before the reset executes.
cat > /etc/systemd/system/eddi-reset.timer << UNIT
[Unit]
Description=Run EDDI demo reset every 48 hours
Requires=eddi-reset.service

[Timer]
OnBootSec=10min
OnUnitActiveSec=48h

[Install]
WantedBy=timers.target
UNIT

systemctl daemon-reload
systemctl enable --now eddi-reset.timer

echo "Installed:  ${DEST}"
echo "Service:    /etc/systemd/system/eddi-reset.service"
echo "Timer:      /etc/systemd/system/eddi-reset.timer  (every 48 h)"
echo "Status:     $(systemctl is-active eddi-reset.timer)"
REMOTE_SCRIPT

  echo ""
  info "Demo-reset timer installed on ${CYAN}${name}${RESET}."
  echo ""
  dim "Runs automatically: every 48 h (systemd timer)"
  dim "Check timer:        $(basename "$0") ssh ${name}  →  sudo systemctl status eddi-reset.timer"
  dim "Trigger manually:   $(basename "$0") ssh ${name}  →  sudo /root/.eddi/eddi-demo-reset.sh"
  dim "Watch log:          $(basename "$0") ssh ${name}  →  tail -f /var/log/eddi-reset.log"
  echo ""
}

# ── Command: list ─────────────────────────────────────────────────────────────

cmd_list() {
  check_prerequisites
  resolve_project

  step "EDDI VMs in project: ${GCP_PROJECT}"

  gcloud compute instances list \
    --project="$GCP_PROJECT" \
    --filter="tags.items=${NETWORK_TAG}" \
    --format="table(name,zone.basename(),machineType.basename(),status,networkInterfaces[0].accessConfigs[0].natIP:label=EXTERNAL_IP)"

  echo ""
}

# ── Command: ssh ──────────────────────────────────────────────────────────────

cmd_ssh() {
  local name="${1:-$VM_NAME}"
  check_prerequisites
  resolve_project

  echo -e "\n  ${BOLD}Connecting to ${name}...${RESET}\n"
  exec gcloud compute ssh "$name" \
    --zone="$ZONE" --project="$GCP_PROJECT"
}

# ── Command: status ───────────────────────────────────────────────────────────

cmd_status() {
  local name="${1:-$VM_NAME}"
  check_prerequisites
  resolve_project

  step "Status: ${name}"

  local vm_status external_ip
  vm_status=$(gcloud compute instances describe "$name" \
    --zone="$ZONE" --project="$GCP_PROJECT" \
    --format='value(status)' 2>/dev/null) || {
    fail "VM '${name}' not found in zone '${ZONE}'."
  }
  external_ip=$(gcloud compute instances describe "$name" \
    --zone="$ZONE" --project="$GCP_PROJECT" \
    --format='value(networkInterfaces[0].accessConfigs[0].natIP)' 2>/dev/null)

  echo -e "  VM state:    ${BOLD}${vm_status}${RESET}"
  echo -e "  External IP: ${CYAN}${external_ip}${RESET}"
  echo ""

  if [[ "$vm_status" != "RUNNING" ]]; then
    warn "VM is not running. Start it with:"
    echo "    gcloud compute instances start ${name} --zone=${ZONE} --project=${GCP_PROJECT}"
    return 0
  fi

  local health_url="http://${external_ip}:${EDDI_PORT}/q/health/ready"
  echo -ne "  EDDI health (HTTP): "
  if curl -sf --connect-timeout 5 "$health_url" &>/dev/null 2>&1; then
    echo -e "${GREEN}✅ ready${RESET}"
    echo ""
    echo -ne "  Deployed agents: "
    local agent_count
    agent_count=$(curl -sf --connect-timeout 5 \
      "http://${external_ip}:${EDDI_PORT}/administration/production/deploymentstatus" \
      2>/dev/null | grep -o '"agentId"' | wc -l | tr -d ' ') || agent_count="0"
    echo -e "${BOLD}${agent_count}${RESET}"
  else
    echo -e "${RED}❌ not ready${RESET}"
    echo "    $(basename "$0") logs ${name}  ← check install progress"
  fi

  local ip_dashes="${external_ip//./-}"
  local https_health="https://${ip_dashes}.sslip.io/q/health/ready"
  echo -ne "  EDDI health (HTTPS): "
  if curl -sf --connect-timeout 5 "$https_health" &>/dev/null 2>&1; then
    echo -e "${GREEN}✅ ready${RESET}"
  else
    echo -e "${DIM}not configured / cert pending${RESET}"
  fi

  echo ""
  echo -e "  ${BOLD}Links:${RESET}"
  echo -e "  HTTP   →  ${CYAN}http://${external_ip}:${EDDI_PORT}/manage${RESET}"
  echo -e "  HTTPS  →  ${CYAN}https://${ip_dashes}.sslip.io/manage${RESET}  ${DIM}(if cert obtained)${RESET}"
  echo -e "  KC     →  ${CYAN}https://auth.${ip_dashes}.sslip.io${RESET}  ${DIM}(if cert obtained)${RESET}"
  echo -e "  MCP    →  ${CYAN}http://${external_ip}:${EDDI_PORT}/mcp${RESET}"
  echo ""
}

# ── Command: logs ─────────────────────────────────────────────────────────────

cmd_logs() {
  local name="${1:-$VM_NAME}"
  check_prerequisites
  resolve_project

  echo ""
  echo -e "  ${BOLD}Streaming install log from '${name}'...${RESET}"
  echo -e "  ${DIM}(Ctrl-C to stop)${RESET}"
  echo ""

  gcloud compute ssh "$name" \
    --zone="$ZONE" --project="$GCP_PROJECT" \
    --command="sudo tail -f /var/log/eddi-install.log 2>/dev/null \
      || sudo journalctl -u google-startup-scripts -f 2>/dev/null \
      || echo 'Log not yet available — VM may still be booting'"
}

# ── Command: ip ───────────────────────────────────────────────────────────────

cmd_ip() {
  local name="${1:-$VM_NAME}"
  check_prerequisites
  resolve_project

  gcloud compute instances describe "$name" \
    --zone="$ZONE" --project="$GCP_PROJECT" \
    --format='value(networkInterfaces[0].accessConfigs[0].natIP)' 2>/dev/null \
    || fail "VM '${name}' not found."
}

# ── Argument parsing ──────────────────────────────────────────────────────────

VM_NAME="$DEFAULT_VM_NAME"
MACHINE_TYPE="$DEFAULT_MACHINE_TYPE"
ZONE="$DEFAULT_ZONE"
DISK_SIZE="$DEFAULT_DISK_SIZE"
IMAGE_FAMILY="$DEFAULT_IMAGE_FAMILY"
IMAGE_PROJECT="$DEFAULT_IMAGE_PROJECT"
EDDI_PORT="$DEFAULT_EDDI_PORT"
EDDI_HTTPS_PORT="$DEFAULT_EDDI_HTTPS_PORT"
EDDI_BRANCH="$DEFAULT_EDDI_BRANCH"
EDDI_VERSION="$DEFAULT_EDDI_VERSION"
GCP_PROJECT=""
WITH_AUTH="false"
WITH_MONITORING="false"
WITH_POSTGRES="false"
SETUP_HTTPS="false"
LETSENCRYPT_EMAIL="admin@example.com"
STATIC_IP="false"
NO_WAIT="false"
VAULT_KEY_ARG=""
EXTERNAL_IP=""
RESERVED_IP=""

COMMAND="${1:-help}"
shift || true

POSITIONAL=()

for arg in "$@"; do
  case "$arg" in
    --name=*)               VM_NAME="${arg#*=}" ;;
    --zone=*)               ZONE="${arg#*=}" ;;
    --machine-type=*)       MACHINE_TYPE="${arg#*=}" ;;
    --disk-size=*)          DISK_SIZE="${arg#*=}" ;;
    --project=*)            GCP_PROJECT="${arg#*=}" ;;
    --eddi-branch=*)        EDDI_BRANCH="${arg#*=}" ;;
    --eddi-version=*)       EDDI_VERSION="${arg#*=}" ;;
    --vault-key=*)          VAULT_KEY_ARG="${arg#*=}" ;;
    --letsencrypt-email=*)  LETSENCRYPT_EMAIL="${arg#*=}" ;;
    --with-auth)            WITH_AUTH="true" ;;
    --with-monitoring)      WITH_MONITORING="true" ;;
    --with-postgres)        WITH_POSTGRES="true" ;;
    --open-access)          WITH_AUTH="false" ;;
    --https)                SETUP_HTTPS="true"; WITH_AUTH="true" ;;
    --no-https)             SETUP_HTTPS="false" ;;
    --static-ip)            STATIC_IP="true" ;;
    --no-wait)              NO_WAIT="true" ;;
    --help|-h)              usage ;;
    --*)
      fail "Unknown option: ${arg}\n     Run: $(basename "$0") --help" ;;
    *)
      POSITIONAL+=("$arg") ;;
  esac
done

# ── Dispatch ──────────────────────────────────────────────────────────────────

case "$COMMAND" in
  create)              cmd_create ;;
  update)              cmd_update "${POSITIONAL[0]:-}" ;;
  install-reset)       cmd_install_reset "${POSITIONAL[0]:-}" ;;
  delete|destroy|rm)   cmd_delete "${POSITIONAL[0]:-}" ;;
  list|ls)             cmd_list ;;
  ssh|connect)         cmd_ssh "${POSITIONAL[0]:-}" ;;
  status|health)       cmd_status "${POSITIONAL[0]:-}" ;;
  logs|log)            cmd_logs "${POSITIONAL[0]:-}" ;;
  ip)                  cmd_ip "${POSITIONAL[0]:-}" ;;
  help|--help|-h) usage ;;
  *)
    echo -e "${RED}Unknown command: ${COMMAND}${RESET}" >&2
    usage
    ;;
esac
