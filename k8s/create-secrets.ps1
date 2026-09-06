#Requires -Version 7
# PowerShell 7 (`pwsh`), not the Windows PowerShell 5.1 that `powershell.exe`
# starts. Two things in here are 7-only, and without this line each fails as raw
# .NET noise partway through a run instead of as one legible sentence before it:
#
#   * New-RandomKey calls [RandomNumberGenerator]::Fill, which exists in .NET
#     Core but not in the .NET Framework 4.x that 5.1 runs on ("does not contain
#     a method named 'Fill'") — the auto-generate path has never worked there.
#   * The `2>&1` captures below rely on 7's native-command redirection. Under 5.1
#     a stderr line captured that way arrives as an ErrorRecord, and with
#     $ErrorActionPreference = 'Stop' the very first "Error from server
#     (NotFound)" — the line EVERY first install produces — throws a terminating
#     RemoteException before the NotFound check below can classify it.
#
# Declared rather than worked around: this script installs a key that cannot be
# recovered if it goes wrong, so "this needs pwsh 7" is a better first line than
# a half-finished install. docs/kubernetes.md says the same next to the command.
# ─────────────────────────────────────────────────────────────
#  EDDI Kubernetes — Secret Generator (PowerShell 7+)
#
#  Creates the eddi-secrets Kubernetes Secret, which holds exactly one thing:
#    - EDDI Vault Master Key (auto-generated or user-provided)
#
#  It does NOT create PostgreSQL credentials. Those are a separate manifest,
#  k8s/overlays/postgres/postgres-secret.yaml, and have to be changed BEFORE the
#  first apply — the postgres image reads the password only during initdb.
#
#  Usage — always through pwsh, never a bare .\…ps1: on a stock Windows box
#  that starts Windows PowerShell 5.1, which the #Requires above refuses.
#    pwsh -File .\k8s\create-secrets.ps1                 # interactive
#    pwsh -File .\k8s\create-secrets.ps1 -Auto           # auto-generate
#    pwsh -File .\k8s\create-secrets.ps1 -Key "my-key"   # use a specific key
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
    Write-Information -MessageData "Usage: pwsh -File .\k8s\create-secrets.ps1 [OPTIONS]" -InformationAction Continue
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
#
# It also has to fail CLOSED. A native command failure does not populate
# $existing and $ErrorActionPreference does not apply to native commands
# (PSNativeCommandUseErrorActionPreference is off by default), so a wrong
# kube-context, an expired token or an RBAC denial read as "no Secret there" and
# the script walked on into the delete below. $LASTEXITCODE is inspected, and
# only a genuine NotFound counts as absent.
#
# "Absent" is recognised by kubectl's STRUCTURED reason — the parenthesised
# "(NotFound)" in "Error from server (NotFound): secrets ... not found" — and not
# by prose. Matching loose English is how the sibling shell script came to read
# "Unable to connect to the server: dial tcp: lookup host: no such host" as
# "there is no Secret here" and walk into the delete on a cluster it had never
# reached.
if (-not $Force) {
    $probe = kubectl get secret eddi-secrets --namespace=$Namespace -o name 2>&1
    $probeExit = $LASTEXITCODE
    $probeText = ($probe | Out-String)
    if ($probeExit -ne 0 -and $probeText -notmatch '\(NotFound\)') {
        Write-Information -MessageData $probeText.Trim() -InformationAction Continue
        Write-Error -Message "  ❌ Could not check whether eddi-secrets already exists (see the kubectl error above). Refusing to continue: a wrong context or a denied request must not be read as 'no key there'."
        exit 1
    }
    $existing = if ($probeExit -eq 0) { $probeText.Trim() } else { "" }
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

# The delete belongs to -Force ALONE.
#
# It used to run unconditionally, justified by the probe above having found
# nothing there to lose. But "absent when probed" is not "absent when deleted".
# Between the two sit the key generation, the menu and — on the custom-passphrase
# path — a Read-Host waiting on a human. Another operator, a second terminal or a
# CI installer creating eddi-secrets inside that window had it erased by a run
# that never passed -Force, printed no warning and exited 0. Conditioning an
# irreversible step on a stale read is a race, not a guard.
#
# Normal creation relies instead on `kubectl create` refusing with AlreadyExists
# — evaluated by the API server against the live object, atomically, at the
# moment of the write. That refusal is handled below.
if ($Force) {
    if ($PSCmdlet.ShouldProcess("Kubernetes", "Delete existing 'eddi-secrets' in '$Namespace' if exists")) {
        kubectl delete secret eddi-secrets --namespace=$Namespace --ignore-not-found 2>$null | Out-Null
    }
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
        # stderr is captured rather than discarded, and the exit code is checked.
        # $ErrorActionPreference = 'Stop' does not apply to native commands, so a
        # failed create used to print the green tick and the "Save this key!" box
        # below for a Secret that was never created — the operator then filed the
        # key and watched the pod sit in ContainerCreating forever.
        $createOutput = kubectl create secret generic eddi-secrets `
            --namespace=$Namespace `
            --from-file=application-secrets.properties=$secretFile 2>&1
        $createExit = $LASTEXITCODE
    }
    finally {
        Remove-Item -Path $secretFile -Force -ErrorAction SilentlyContinue
    }
    if ($createExit -ne 0) {
        Write-Information -MessageData ($createOutput | Out-String).Trim() -InformationAction Continue
        # AlreadyExists is the atomic half of the guard, not a generic failure:
        # the Secret was created after this run's probe said it was not there.
        # Nothing was destroyed — the delete above is force-only — so the message
        # says so rather than leaving the operator to wonder.
        if (($createOutput | Out-String) -match '\(AlreadyExists\)') {
            Write-Error -Message "  ❌ eddi-secrets already exists in namespace $Namespace — it appeared between this run's check and its create, and NOTHING was changed. The key generated here was not installed and the live one is untouched. To rotate deliberately, re-run with -Force."
            exit 1
        }
        Write-Error -Message "  ❌ kubectl create secret failed — eddi-secrets was NOT created and the key was not installed."
        exit 1
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
