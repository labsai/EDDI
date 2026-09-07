#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
#  eddi-demo-reset.sh — Wipes EDDI session data every 48 hours
#
#  Clears (session / runtime data):
#    conversationmemories    all chat history
#    usermemories            long-term user properties
#    userconversations       user ↔ conversation index
#    conversation_checkpoints  forked-conversation snapshots
#    agenttriggers           runtime trigger records
#    audit_ledger            audit log entries
#    logs                    internal DB logs
#    tenant_usage            per-tenant usage counters
#
#  Preserves (configuration / secrets):
#    agent configs, workflows, behaviors, outputs, llms, apicalls, ...
#    secretvault_*           encrypted API keys — NEVER cleared
#    eddi_schedules          scheduled job definitions
#    globalvariables         deployment-wide config
#    migrationlog            schema migration state
#
#  After clearing, EDDI is restarted and the Agent Father is re-imported.
#
#  ── Install on the VM ────────────────────────────────────────────────────────
#
#  From your local machine (recommended):
#    ./gcp/provision-vm.sh install-reset eddi-demo
#
#  Or manually on the VM:
#    sudo install -m 0755 eddi-demo-reset.sh /root/.eddi/eddi-demo-reset.sh
#    # then create /etc/systemd/system/eddi-reset.{service,timer} — see
#    # cmd_install_reset() in provision-vm.sh for the exact unit file content.
#
#  ── Manual run ───────────────────────────────────────────────────────────────
#
#    sudo /root/.eddi/eddi-demo-reset.sh
#
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

EDDI_DIR="${EDDI_DIR:-/root/.eddi}"
EDDI_PORT="${EDDI_PORT:-7070}"
LOGFILE="/var/log/eddi-reset.log"

# ── Helpers ───────────────────────────────────────────────────────────────────

log()  { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" | tee -a "$LOGFILE"; }
fail() { log "ERROR: $*"; exit 1; }

log "=== EDDI demo reset starting ==="

# ── Load install config ───────────────────────────────────────────────────────

CONFIG_FILE="$EDDI_DIR/.eddi-config"
ENV_FILE="$EDDI_DIR/.env"

[[ -f "$CONFIG_FILE" ]] || fail "$CONFIG_FILE not found — is EDDI installed?"
[[ -f "$ENV_FILE" ]]    || fail "$ENV_FILE not found — is EDDI installed?"

# Safe config parsing (no eval)
_cfg() { grep "^$1=" "$CONFIG_FILE" 2>/dev/null | head -1 | cut -d= -f2- | sed 's/^"//;s/"$//'; }
_env() { grep "^$1=" "$ENV_FILE"    2>/dev/null | head -1 | cut -d= -f2- | sed 's/^"//;s/"$//'; }

COMPOSE_FILES_STR=$(_cfg COMPOSE_FILES)
read -ra CF_ARRAY <<< "$COMPOSE_FILES_STR"

COMPOSE_ARGS=(--env-file "$ENV_FILE")
for f in "${CF_ARRAY[@]}"; do
  [[ -n "$f" ]] && COMPOSE_ARGS+=(-f "$f")
done

# MongoDB credentials (fall back to EDDI docker-compose defaults)
MONGO_USER=$(_env MONGO_INITDB_ROOT_USERNAME); MONGO_USER="${MONGO_USER:-eddi}"
MONGO_PASS=$(_env MONGO_INITDB_ROOT_PASSWORD); MONGO_PASS="${MONGO_PASS:-changeme}"

log "Compose files: ${CF_ARRAY[*]}"

# ── Verify MongoDB is running ─────────────────────────────────────────────────

if ! docker compose "${COMPOSE_ARGS[@]}" ps --status running mongodb \
    2>/dev/null | grep -q "running"; then
  fail "MongoDB container is not running. Aborting reset."
fi

# ── Clear session collections ─────────────────────────────────────────────────

log "Clearing session collections..."
MONGOSH_OUTPUT=$(docker compose "${COMPOSE_ARGS[@]}" exec -T mongodb mongosh \
  --username  "$MONGO_USER" \
  --password  "$MONGO_PASS" \
  --authenticationDatabase admin \
  --quiet \
  --eval '
    var d = db.getSiblingDB("eddi");
    var cols = [
      "conversationmemories",
      "usermemories",
      "userconversations",
      "conversation_checkpoints",
      "agenttriggers",
      "audit_ledger",
      "logs",
      "tenant_usage"
    ];
    var totals = {};
    cols.forEach(function(c) {
      var r = d.getCollection(c).deleteMany({});
      totals[c] = r.deletedCount;
    });
    var summary = Object.keys(totals).map(function(k) {
      return k + ": " + totals[k];
    }).join(", ");
    print("Deleted — " + summary);
  ' 2>&1) || fail "mongosh failed: $MONGOSH_OUTPUT"

log "$MONGOSH_OUTPUT"

# ── Restart EDDI (MongoDB stays up) ──────────────────────────────────────────

log "Restarting EDDI container..."
docker compose "${COMPOSE_ARGS[@]}" restart eddi
log "EDDI container restarted."

# ── Wait for health ───────────────────────────────────────────────────────────

log "Waiting for EDDI to become healthy..."
HEALTH_URL="http://localhost:$EDDI_PORT/q/health/ready"
HEALTHY=false
for i in $(seq 1 36); do
  if curl -sf --connect-timeout 5 "$HEALTH_URL" &>/dev/null; then
    log "EDDI healthy after $((i * 5))s."
    HEALTHY=true
    break
  fi
  sleep 5
done

if [[ "$HEALTHY" != "true" ]]; then
  fail "EDDI did not become healthy within 180s. Check: docker compose logs eddi"
fi

# ── Re-import initial agents ──────────────────────────────────────────────────

log "Re-importing initial agents (Agent Father + samples)..."
STATUS=$(curl -sf -o /dev/null -w "%{http_code}" \
  -X POST "http://localhost:$EDDI_PORT/backup/import/initialAgents" 2>/dev/null) \
  || STATUS="000"

case "$STATUS" in
  200) log "Initial agents imported successfully." ;;
  409) log "Initial agents already present — skipping import." ;;
  *)   log "WARN: /backup/import/initialAgents returned HTTP $STATUS (non-fatal)." ;;
esac

log "=== EDDI demo reset complete ==="
