#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
#  EDDI Kubernetes — Secret Generator
#
#  Creates the Kubernetes Secret with:
#    - EDDI Vault Master Key (auto-generated or user-provided)
#    - Optional PostgreSQL credentials
#
#  Usage:
#    bash k8s/create-secrets.sh                 # interactive
#    bash k8s/create-secrets.sh --auto          # auto-generate, no prompts
#    bash k8s/create-secrets.sh --key="my-key"  # use a specific key
# ─────────────────────────────────────────────────────────────
set -euo pipefail

NAMESPACE="${EDDI_NAMESPACE:-eddi}"
AUTO=false
FORCE=false
VAULT_KEY=""

# Colors
if [[ -t 1 ]]; then
  BOLD='\033[1m' GREEN='\033[0;32m' YELLOW='\033[0;33m'
  RED='\033[0;31m' CYAN='\033[0;36m' DIM='\033[2m' RESET='\033[0m'
else
  BOLD='' GREEN='' YELLOW='' RED='' CYAN='' DIM='' RESET=''
fi

info()    { echo -e "  ${GREEN}✅${RESET} $1"; }
warn()    { echo -e "  ${YELLOW}⚠️  $1${RESET}"; }
fail()    { echo -e "  ${RED}❌ $1${RESET}"; exit 1; }

# Parse args
for arg in "$@"; do
  case "$arg" in
    --auto)       AUTO=true ;;
    --force)      FORCE=true ;;
    --key=*)      VAULT_KEY="${arg#*=}" ;;
    --namespace=*) NAMESPACE="${arg#*=}" ;;
    --help|-h)
      echo "EDDI Kubernetes Secret Generator"
      echo ""
      echo "Usage: bash k8s/create-secrets.sh [OPTIONS]"
      echo ""
      echo "Options:"
      echo "  --auto                  Auto-generate key, no prompts"
      echo "  --force                 Replace an existing eddi-secrets (DESTROYS the current"
      echo "                          master key — everything encrypted with it is then lost)"
      echo "  --key=<key>             Use a specific vault key (min 16 chars)"
      echo "  --namespace=<ns>        Kubernetes namespace (default: eddi)"
      echo ""
      exit 0
      ;;
  esac
done

# Check prerequisites
if ! command -v kubectl &>/dev/null; then
  fail "kubectl is required but not found. Install: https://kubernetes.io/docs/tasks/tools/"
fi

echo ""
echo -e "${BOLD}  EDDI — Kubernetes Secret Generator${RESET}"
echo ""

# Refuse to replace a live master key.
#
# This script installs a NEW key, so replacing the Secret makes every API key
# and secret already encrypted under the old one permanently undecryptable.
# Dropping the Secret from the shipped manifests closed that trap for
# `kubectl apply -k`; it must not reopen here, now that the docs route every
# install through this script. Checked BEFORE the key is generated or prompted
# for, so nobody types a passphrase that is then thrown away.
#
# The delete that makes --force able to rotate lives BELOW this check, not
# above it: run first it would remove the very Secret the check looks for, the
# `kubectl get` would then find nothing, and the guard would wave every run
# through after the key it protects had already been destroyed.
if [[ "$FORCE" != "true" ]] && kubectl get secret eddi-secrets --namespace="$NAMESPACE" &>/dev/null; then
  warn "eddi-secrets already exists in namespace ${NAMESPACE} — nothing was changed."
  echo ""
  echo -e "  Replacing it installs a ${BOLD}new${RESET} master key, and everything encrypted"
  echo -e "  under the current one becomes ${BOLD}permanently undecryptable${RESET}."
  echo ""
  echo -e "  To read the key already in the cluster:"
  echo -e "    ${CYAN}kubectl get secret eddi-secrets -n ${NAMESPACE} \\"
  echo -e "      -o jsonpath='{.data.application-secrets\.properties}' | base64 -d${RESET}"
  echo ""
  echo -e "  To rotate deliberately, re-run with ${BOLD}--force${RESET}."
  echo ""
  exit 1
fi

# Generate or accept vault key
if [[ -n "$VAULT_KEY" ]]; then
  if [[ ${#VAULT_KEY} -lt 16 ]]; then
    fail "Vault key must be at least 16 characters (got ${#VAULT_KEY})"
  fi
  info "Using provided vault key"
elif [[ "$AUTO" == "true" ]]; then
  VAULT_KEY=$(openssl rand -base64 24 2>/dev/null || head -c 24 /dev/urandom | base64)
  info "Vault master key auto-generated"
else
  echo -e "  EDDI encrypts API keys and secrets using a vault master key."
  echo -e "  This key is unique to your installation — ${BOLD}keep it safe!${RESET}"
  echo ""
  echo -e "  ${BOLD}1)${RESET} Auto-generate  ${DIM}strong random key (recommended)${RESET}"
  echo -e "  ${BOLD}2)${RESET} Custom         ${DIM}enter your own passphrase (min 16 chars)${RESET}"
  echo ""
  echo -ne "  Choose [1]: "
  read -r choice
  choice="${choice:-1}"

  if [[ "$choice" == "1" ]]; then
    VAULT_KEY=$(openssl rand -base64 24 2>/dev/null || head -c 24 /dev/urandom | base64)
    info "Vault master key generated"
  else
    while true; do
      echo -ne "  Enter passphrase: "
      read -rs passphrase
      echo ""
      if [[ ${#passphrase} -lt 16 ]]; then
        warn "Passphrase must be at least 16 characters"
      else
        VAULT_KEY="$passphrase"
        info "Custom passphrase set"
        break
      fi
    done
  fi
fi

# Create namespace if it doesn't exist
if ! kubectl get namespace "$NAMESPACE" &>/dev/null 2>&1; then
  echo -ne "  Creating namespace ${NAMESPACE}... "
  kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f - >/dev/null 2>&1
  echo -e "${GREEN}✅${RESET}"
fi

# Delete existing secret if it exists (to avoid "already exists" error).
# Only reachable with --force, or with nothing there to lose: the guard above
# has already aborted otherwise.
kubectl delete secret eddi-secrets --namespace="$NAMESPACE" --ignore-not-found >/dev/null 2>&1

# Create the secret.
#
# One key, "application-secrets.properties", holding a Quarkus properties file.
# The Deployment mounts it as a file (projected volume, mode 0400) and points
# QUARKUS_CONFIG_LOCATIONS at it — secrets are deliberately NOT injected as
# environment variables, which are readable from /proc/<pid>/environ and leak
# into crash dumps and child processes.
echo -ne "  Creating eddi-secrets... "
SECRET_FILE=$(mktemp "${TMPDIR:-/tmp}/eddi-secrets.XXXXXX")
chmod 600 "$SECRET_FILE"
cleanup_secret_file() { rm -f "$SECRET_FILE"; }
trap cleanup_secret_file EXIT
printf 'eddi.vault.master-key=%s\n' "$VAULT_KEY" > "$SECRET_FILE"

kubectl create secret generic eddi-secrets \
  --namespace="$NAMESPACE" \
  --from-file=application-secrets.properties="$SECRET_FILE" \
  >/dev/null 2>&1
cleanup_secret_file
trap - EXIT
echo -e "${GREEN}✅${RESET}"

echo ""
echo -e "  ${YELLOW}┌─ 🔑 Vault Master Key ──────────────────────────────┐${RESET}"
echo -e "  ${YELLOW}│                                                    │${RESET}"
echo -e "  ${YELLOW}│${RESET}  ${BOLD}${VAULT_KEY}${RESET}"
echo -e "  ${YELLOW}│                                                    │${RESET}"
echo -e "  ${YELLOW}│${RESET}  ${DIM}⚠️  Save this key! If lost, encrypted secrets${RESET}"
echo -e "  ${YELLOW}│${RESET}  ${DIM}   (API keys) are UNRECOVERABLE.${RESET}"
echo -e "  ${YELLOW}│                                                    │${RESET}"
echo -e "  ${YELLOW}└────────────────────────────────────────────────────┘${RESET}"
echo ""
echo -e "  Secret created in namespace: ${CYAN}${NAMESPACE}${RESET}"
echo ""
echo -e "  ${BOLD}Next steps:${RESET}"
echo "    kubectl apply -k k8s/overlays/mongodb/    # MongoDB backend"
echo "    kubectl apply -k k8s/overlays/postgres/   # PostgreSQL backend"
echo ""
