#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
#  EDDI Kubernetes — Secret Generator
#
#  Creates the eddi-secrets Kubernetes Secret, which holds exactly one thing:
#    - EDDI Vault Master Key (auto-generated or user-provided)
#
#  It does NOT create PostgreSQL credentials. Those are a separate manifest,
#  k8s/overlays/postgres/postgres-secret.yaml, and have to be changed BEFORE the
#  first apply — the postgres image reads the password only during initdb.
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
#
# The check also has to fail CLOSED. Any nonzero `kubectl get` used to read as
# "no Secret there" — including a wrong kube-context, an expired token, an RBAC
# denial or an unreachable API server — and the script then walked straight into
# the delete on a cluster whose contents it had not actually been able to see.
# Only a genuine NotFound counts as absent; anything else aborts.
#
# "Genuine NotFound" is kubectl's STRUCTURED reason — the parenthesised
# "(NotFound)" carried by every "Error from server (NotFound): secrets ... not
# found" — and nothing looser. Matching prose let an UNREACHABLE cluster back in
# through the side door: kubectl answers a DNS failure with "Unable to connect
# to the server: dial tcp: lookup <host>: no such host", which the earlier
# 'not found|no such|notfound' alternation read as "there is no Secret here".
# The script then prompted for a key and, if the name resolved again before the
# unconditional delete below (a VPN reconnecting is enough), destroyed a live
# master key on a cluster this probe had never actually seen. Nothing kubectl
# prints for a missing object omits the reason in parentheses.
if [[ "$FORCE" != "true" ]]; then
  if secret_probe=$(kubectl get secret eddi-secrets --namespace="$NAMESPACE" -o name 2>&1); then
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
  elif ! grep -qF '(NotFound)' <<<"$secret_probe"; then
    echo -e "  ${DIM}${secret_probe}${RESET}" >&2
    fail "Could not check whether eddi-secrets already exists (see the kubectl error above). Refusing to continue: a wrong context or a denied request must not be read as 'no key there'."
  fi
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

# The delete belongs to --force ALONE.
#
# It used to run unconditionally, justified by the guard above having found
# nothing there to lose. But "absent when probed" is not "absent when deleted".
# Between the two sit the key generation, the interactive menu and — on the
# custom-passphrase path — an unbounded `read` waiting on a human. Another
# operator, a second terminal or a CI installer creating eddi-secrets inside
# that window had it erased by a run that never passed --force, printed no
# warning and exited 0. Conditioning an irreversible step on a stale read is a
# race, not a guard, and this is the one irreversible step in the file.
#
# Normal creation relies instead on `kubectl create` refusing with AlreadyExists
# — evaluated by the API server against the live object, atomically, at the
# moment of the write rather than against what this script saw a minute ago.
# That refusal is handled below.
if [[ "$FORCE" == "true" ]]; then
  kubectl delete secret eddi-secrets --namespace="$NAMESPACE" --ignore-not-found >/dev/null 2>&1
fi

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

#
# stderr is NOT discarded. It used to be, so a failed create (wrong context,
# expired token, RBAC denial) printed nothing at all and `set -e` aborted the
# script right after the "Creating eddi-secrets... " prefix — no message, no
# explanation, and on a shell without errexit the "Save this key!" box below
# would have been printed for a Secret that does not exist.
if ! create_error=$(kubectl create secret generic eddi-secrets \
  --namespace="$NAMESPACE" \
  --from-file=application-secrets.properties="$SECRET_FILE" 2>&1); then
  cleanup_secret_file
  trap - EXIT
  echo ""
  echo -e "  ${DIM}${create_error}${RESET}" >&2
  # AlreadyExists is the atomic half of the guard, not a generic failure: the
  # Secret was created after this run's probe said it was not there. Nothing was
  # destroyed — the delete above is force-only — so the message says so, rather
  # than leaving the operator to wonder whether the live key survived.
  if grep -qF '(AlreadyExists)' <<<"$create_error"; then
    fail "eddi-secrets already exists in namespace ${NAMESPACE} — it appeared between this run's check and its create, and NOTHING was changed. The key generated here was not installed and the live one is untouched. To rotate deliberately, re-run with --force."
  fi
  # "the key" — never "the key above". Nothing above this point has printed a
  # key: the only earlier output is the one-line "Using provided vault key" /
  # "auto-generated" notice, and the value itself is printed in the box further
  # down, which this branch never reaches.
  fail "kubectl create secret failed — eddi-secrets was NOT created and the key was not installed."
fi
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
