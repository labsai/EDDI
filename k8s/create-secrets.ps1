# ─────────────────────────────────────────────────────────────
#  EDDI Kubernetes — Secret Generator (PowerShell)
#
#  Creates the Kubernetes Secret with:
#    - EDDI Vault Master Key (auto-generated or user-provided)
#
#  Usage:
#    .\k8s\create-secrets.ps1                 # interactive
#    .\k8s\create-secrets.ps1 -Auto           # auto-generate
#    .\k8s\create-secrets.ps1 -Key "my-key"   # use a specific key
# ─────────────────────────────────────────────────────────────

[CmdletBinding(SupportsShouldProcess)]
param(
    [switch]$Auto,
    # Replace an existing eddi-secrets. DESTROYS the current master key —
    # everything encrypted with it is then unrecoverable.
    [switch]$Force,
    [string]$Key = "",
    [string]$Namespace = "eddi",
    [switch]$Help
)

$ErrorActionPreference = "Stop"

if ($Help) {
    Write-Information -MessageData "EDDI Kubernetes Secret Generator (PowerShell)" -InformationAction Continue
    Write-Information -MessageData "" -InformationAction Continue
    Write-Information -MessageData "Usage: .\k8s\create-secrets.ps1 [OPTIONS]" -InformationAction Continue
    Write-Information -MessageData "" -InformationAction Continue
    Write-Information -MessageData "Options:" -InformationAction Continue
    Write-Information -MessageData "  -Auto                  Auto-generate key, no prompts" -InformationAction Continue
    Write-Information -MessageData "  -Force                 Replace an existing eddi-secrets (DESTROYS the current key)" -InformationAction Continue
    Write-Information -MessageData "  -Key <key>             Use a specific vault key (min 16 chars)" -InformationAction Continue
    Write-Information -MessageData "  -Namespace <ns>        Kubernetes namespace (default: eddi)" -InformationAction Continue
    Write-Information -MessageData "" -InformationAction Continue
    exit 0
}

# Check prerequisites
if (-not (Get-Command -Name kubectl -ErrorAction SilentlyContinue)) {
    Write-Error -Message "  ❌ kubectl is required but not found."
    Write-Information -MessageData "     Install: https://kubernetes.io/docs/tasks/tools/" -InformationAction Continue
    exit 1
}

function New-RandomKey {
    $bytes = New-Object -TypeName byte[] -ArgumentList 24
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToBase64String($bytes)
}

Write-Information -MessageData "" -InformationAction Continue
Write-Information -MessageData "  EDDI — Kubernetes Secret Generator" -InformationAction Continue
Write-Information -MessageData "" -InformationAction Continue

# Refuse to replace a live master key.
#
# This script installs a NEW key, so replacing the Secret makes every API key and
# secret already encrypted under the old one permanently undecryptable. Dropping
# the Secret from the shipped manifests closed that trap for `kubectl apply -k`;
# it must not reopen here, now that the docs route every install through this
# script. Checked BEFORE the key is generated or prompted for, so nobody types a
# passphrase that is then thrown away.
if (-not $Force) {
    $existing = kubectl get secret eddi-secrets --namespace=$Namespace --ignore-not-found 2>$null
    if ($existing) {
        Write-Information -MessageData "  ⚠️  eddi-secrets already exists in namespace $Namespace — nothing was changed." -InformationAction Continue
        Write-Information -MessageData "" -InformationAction Continue
        Write-Information -MessageData "  Replacing it installs a NEW master key, and everything encrypted under the" -InformationAction Continue
        Write-Information -MessageData "  current one becomes PERMANENTLY UNDECRYPTABLE." -InformationAction Continue
        Write-Information -MessageData "" -InformationAction Continue
        Write-Information -MessageData "  To read the key already in the cluster:" -InformationAction Continue
        Write-Information -MessageData "    kubectl get secret eddi-secrets -n $Namespace -o jsonpath='{.data.application-secrets\.properties}'" -InformationAction Continue
        Write-Information -MessageData "" -InformationAction Continue
        Write-Information -MessageData "  To rotate deliberately, re-run with -Force." -InformationAction Continue
        Write-Information -MessageData "" -InformationAction Continue
        exit 1
    }
}

$VaultKey = ""

if ($Key -ne "") {
    if ($Key.Length -lt 16) {
        Write-Error -Message "  ❌ Vault key must be at least 16 characters (got $($Key.Length))"
        exit 1
    }
    $VaultKey = $Key
    Write-Information -MessageData "  ✅ Using provided vault key" -InformationAction Continue
}
elseif ($Auto) {
    $VaultKey = New-RandomKey
    Write-Information -MessageData "  ✅ Vault master key auto-generated" -InformationAction Continue
}
else {
    Write-Information -MessageData "  EDDI encrypts API keys and secrets using a vault master key." -InformationAction Continue
    Write-Information -MessageData "  This key is unique to your installation — keep it safe!" -InformationAction Continue
    Write-Information -MessageData "" -InformationAction Continue
    Write-Information -MessageData "  1) Auto-generate  (strong random key, recommended)" -InformationAction Continue
    Write-Information -MessageData "  2) Custom         (enter your own passphrase, min 16 chars)" -InformationAction Continue
    Write-Information -MessageData "" -InformationAction Continue
    
    $choice = Read-Host -Prompt "  Choose [1]"
    if ([string]::IsNullOrWhiteSpace($choice)) { $choice = "1" }

    if ($choice -eq "1") {
        $VaultKey = New-RandomKey
        Write-Information -MessageData "  ✅ Vault master key generated" -InformationAction Continue
    }
    else {
        while ($true) {
            $passphrase = Read-Host -Prompt "  Enter passphrase" -AsSecureString
            $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($passphrase)
            $plain = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
            [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)

            if ($plain.Length -lt 16) {
                Write-Warning -Message "  ⚠️  Passphrase must be at least 16 characters"
            }
            else {
                $VaultKey = $plain
                Write-Information -MessageData "  ✅ Custom passphrase set" -InformationAction Continue
                break
            }
        }
    }
}

# Create namespace if it doesn't exist
$nsExists = kubectl get namespace $Namespace 2>$null
if (-not $nsExists) {
    if ($PSCmdlet.ShouldProcess("Kubernetes", "Create namespace '$Namespace'")) {
        kubectl create namespace $Namespace --dry-run=client -o yaml | kubectl apply -f - 2>$null | Out-Null
        Write-Information -MessageData "  Creating namespace $Namespace... ✅" -InformationAction Continue
    }
}

# Delete existing secret if it exists
if ($PSCmdlet.ShouldProcess("Kubernetes", "Delete existing 'eddi-secrets' in '$Namespace' if exists")) {
    kubectl delete secret eddi-secrets --namespace=$Namespace --ignore-not-found 2>$null | Out-Null
}

# Create the secret.
#
# One key, "application-secrets.properties", holding a Quarkus properties file.
# The Deployment mounts it as a file (projected volume, mode 0400) and points
# QUARKUS_CONFIG_LOCATIONS at it — secrets are deliberately NOT injected as
# environment variables, which are readable from /proc/<pid>/environ and leak
# into crash dumps and child processes.
if ($PSCmdlet.ShouldProcess("Kubernetes", "Create secret 'eddi-secrets' in '$Namespace'")) {
    $secretFile = Join-Path ([System.IO.Path]::GetTempPath()) ("eddi-secrets-" + [guid]::NewGuid().ToString() + ".properties")
    try {
        Set-Content -Path $secretFile -Value "eddi.vault.master-key=$VaultKey" -Encoding utf8 -NoNewline
        kubectl create secret generic eddi-secrets `
            --namespace=$Namespace `
            --from-file=application-secrets.properties=$secretFile 2>$null | Out-Null
    }
    finally {
        Remove-Item -Path $secretFile -Force -ErrorAction SilentlyContinue
    }
    Write-Information -MessageData "  Creating eddi-secrets... ✅" -InformationAction Continue
}

Write-Information -MessageData "" -InformationAction Continue
Write-Information -MessageData "  ┌─ 🔑 Vault Master Key ──────────────────────────────┐" -InformationAction Continue
Write-Information -MessageData "  │                                                    │" -InformationAction Continue
Write-Information -MessageData "  │  $VaultKey" -InformationAction Continue
Write-Information -MessageData "  │                                                    │" -InformationAction Continue
Write-Information -MessageData "  │  ⚠️  Save this key! If lost, encrypted secrets     │" -InformationAction Continue
Write-Information -MessageData "  │     (API keys) are UNRECOVERABLE.                  │" -InformationAction Continue
Write-Information -MessageData "  │                                                    │" -InformationAction Continue
Write-Information -MessageData "  └────────────────────────────────────────────────────┘" -InformationAction Continue
Write-Information -MessageData "" -InformationAction Continue
Write-Information -MessageData "  Secret created in namespace: $Namespace" -InformationAction Continue
Write-Information -MessageData "" -InformationAction Continue
Write-Information -MessageData "  Next steps:" -InformationAction Continue
Write-Information -MessageData "    kubectl apply -k k8s/overlays/mongodb/    # MongoDB backend" -InformationAction Continue
Write-Information -MessageData "    kubectl apply -k k8s/overlays/postgres/   # PostgreSQL backend" -InformationAction Continue
Write-Information -MessageData "" -InformationAction Continue
