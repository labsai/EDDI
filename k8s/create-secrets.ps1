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

[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSAvoidUsingWriteHost', '')]
param(
    [switch]$Auto,
    [string]$Key = "",
    [string]$Namespace = "eddi",
    [switch]$Help
)

$ErrorActionPreference = "Stop"

if ($Help) {
    Write-Host "EDDI Kubernetes Secret Generator (PowerShell)"
    Write-Host ""
    Write-Host "Usage: .\k8s\create-secrets.ps1 [OPTIONS]"
    Write-Host ""
    Write-Host "Options:"
    Write-Host "  -Auto                  Auto-generate key, no prompts"
    Write-Host "  -Key <key>             Use a specific vault key (min 16 chars)"
    Write-Host "  -Namespace <ns>        Kubernetes namespace (default: eddi)"
    Write-Host ""
    exit 0
}

# Check prerequisites
if (-not (Get-Command kubectl -ErrorAction SilentlyContinue)) {
    Write-Host "  ❌ kubectl is required but not found." -ForegroundColor Red
    Write-Host "     Install: https://kubernetes.io/docs/tasks/tools/"
    exit 1
}

function New-RandomKey {
    [CmdletBinding(SupportsShouldProcess)]
    param()
    if (-not $PSCmdlet.ShouldProcess("Secrets", "Generate Random Key")) { return }
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToBase64String($bytes)
}

Write-Host ""
Write-Host "  EDDI — Kubernetes Secret Generator" -ForegroundColor White
Write-Host ""

$VaultKey = ""

if ($Key -ne "") {
    if ($Key.Length -lt 16) {
        Write-Host "  ❌ Vault key must be at least 16 characters (got $($Key.Length))" -ForegroundColor Red
        exit 1
    }
    $VaultKey = $Key
    Write-Host "  ✅ Using provided vault key" -ForegroundColor Green
}
elseif ($Auto) {
    $VaultKey = New-RandomKey
    Write-Host "  ✅ Vault master key auto-generated" -ForegroundColor Green
}
else {
    Write-Host "  EDDI encrypts API keys and secrets using a vault master key."
    Write-Host "  This key is unique to your installation — keep it safe!"
    Write-Host ""
    Write-Host "  1) Auto-generate  (strong random key, recommended)"
    Write-Host "  2) Custom         (enter your own passphrase, min 16 chars)"
    Write-Host ""
    $choice = Read-Host "  Choose [1]"
    if ([string]::IsNullOrWhiteSpace($choice)) { $choice = "1" }

    if ($choice -eq "1") {
        $VaultKey = New-RandomKey
        Write-Host "  ✅ Vault master key generated" -ForegroundColor Green
    }
    else {
        while ($true) {
            $passphrase = Read-Host "  Enter passphrase" -AsSecureString
            $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($passphrase)
            $plain = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
            [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)

            if ($plain.Length -lt 16) {
                Write-Host "  ⚠️  Passphrase must be at least 16 characters" -ForegroundColor Yellow
            }
            else {
                $VaultKey = $plain
                Write-Host "  ✅ Custom passphrase set" -ForegroundColor Green
                break
            }
        }
    }
}

# Create namespace if it doesn't exist
$nsExists = kubectl get namespace $Namespace 2>$null
if (-not $nsExists) {
    Write-Host -NoNewline "  Creating namespace $Namespace... "
    kubectl create namespace $Namespace --dry-run=client -o yaml | kubectl apply -f - 2>$null | Out-Null
    Write-Host "✅" -ForegroundColor Green
}

# Delete existing secret if it exists
kubectl delete secret eddi-secrets --namespace=$Namespace --ignore-not-found 2>$null | Out-Null

# Create the secret
Write-Host -NoNewline "  Creating eddi-secrets... "
kubectl create secret generic eddi-secrets `
    --namespace=$Namespace `
    --from-literal=EDDI_VAULT_MASTER_KEY="$VaultKey" 2>$null | Out-Null
Write-Host "✅" -ForegroundColor Green

Write-Host ""
Write-Host "  ┌─ 🔑 Vault Master Key ──────────────────────────────┐" -ForegroundColor Yellow
Write-Host "  │                                                    │" -ForegroundColor Yellow
Write-Host "  │  $VaultKey" -ForegroundColor White
Write-Host "  │                                                    │" -ForegroundColor Yellow
Write-Host "  │  ⚠️  Save this key! If lost, encrypted secrets     │" -ForegroundColor DarkGray
Write-Host "  │     (API keys) are UNRECOVERABLE.                  │" -ForegroundColor DarkGray
Write-Host "  │                                                    │" -ForegroundColor Yellow
Write-Host "  └────────────────────────────────────────────────────┘" -ForegroundColor Yellow
Write-Host ""
Write-Host "  Secret created in namespace: $Namespace" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Next steps:" -ForegroundColor White
Write-Host "    kubectl apply -k k8s/overlays/mongodb/    # MongoDB backend"
Write-Host "    kubectl apply -k k8s/overlays/postgres/   # PostgreSQL backend"
Write-Host ""
