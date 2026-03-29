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
    --key=*)      VAULT_KEY="${arg#*=}" ;;
    --namespace=*) NAMESPACE="${arg#*=}" ;;
    --help|-h)
      echo "EDDI Kubernetes Secret Generator"
      echo ""
      echo "Usage: bash k8s/create-secrets.sh [OPTIONS]"
      echo ""
      echo "Options:"
      echo "  --auto                  Auto-generate key, no prompts"
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

# Delete existing secret if it exists (to avoid "already exists" error)
kubectl delete secret eddi-secrets --namespace="$NAMESPACE" --ignore-not-found >/dev/null 2>&1

# Create the secret
echo -ne "  Creating eddi-secrets... "
kubectl create secret generic eddi-secrets \
  --namespace="$NAMESPACE" \
  --from-literal=EDDI_VAULT_MASTER_KEY="$VAULT_KEY" \
  >/dev/null 2>&1
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
