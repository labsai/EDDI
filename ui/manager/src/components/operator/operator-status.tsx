import { useState } from "react";
import { useTranslation } from "react-i18next";
import {
  Power,
  Lock,
  Cpu,
  AlertTriangle,
  CheckCircle2,
  Loader2,
  RefreshCw,
  Trash2,
  PlugZap,
  ShieldCheck,
  ShieldAlert,
  ShieldQuestion,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { AlertDialog } from "@/components/ui/alert-dialog";
import type { OperatorConfig, GateVerificationResult } from "@/lib/api/operator";
import type { DeploymentStatus } from "@/lib/api/agents";

interface OperatorStatusPanelProps {
  config: OperatorConfig;
  status: DeploymentStatus | null | undefined;
  statusLoading: boolean;
  /** Result of re-reading the approval gate off the live agent document. */
  gate: GateVerificationResult | null | undefined;
  gateLoading: boolean;
  onReconfigure: () => void;
  onDeactivate: () => void;
  onReset: () => void;
  /** Re-run the probe read that proves the operator can reach the platform. */
  onRecheck: () => void;
  recheckPending?: boolean;
  busy?: boolean;
}

export function OperatorStatusPanel({
  config,
  status,
  statusLoading,
  gate,
  gateLoading,
  onReconfigure,
  onDeactivate,
  onReset,
  onRecheck,
  recheckPending = false,
  busy = false,
}: OperatorStatusPanelProps) {
  const { t } = useTranslation();
  const [confirmDeactivate, setConfirmDeactivate] = useState(false);
  const [confirmReset, setConfirmReset] = useState(false);

  const state = status?.status;

  return (
    <Card className="h-fit">
      <CardHeader>
        <CardTitle className="text-base">{t("operator.status.title", "Operator")}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="flex flex-wrap gap-2">
          <Badge variant="secondary" className="gap-1">
            <Cpu className="h-3 w-3" />
            {config.model}
          </Badge>
          {/* Derived from the CONFIGURED scope, never hardcoded: this panel is
              where an admin answers "what can this thing do right now?", and a
              green padlock on a write-capable operator is the one wrong answer
              that matters. Mirrors the same two-branch chip in the activation
              form so the two surfaces cannot disagree. */}
          {config.scope === "read_write" ? (
            <Badge variant="warning" className="gap-1" data-testid="operator-status-scope-chip">
              <ShieldAlert className="h-3 w-3" />
              {t("operator.readWriteChip", "Read & write")}
            </Badge>
          ) : (
            <Badge variant="success" className="gap-1" data-testid="operator-status-scope-chip">
              <Lock className="h-3 w-3" />
              {t("operator.readOnlyChip", "Read-only")}
            </Badge>
          )}
          <GateBadge gate={gate} loading={gateLoading} />
        </div>

        <div className="space-y-1 text-sm">
          <Row label={t("operator.status.provider", "Provider")} value={config.provider} />
          <Row label={t("operator.status.environment", "Environment")} value={config.environment} />
          <Row
            label={t("operator.status.authMode", "Authenticates as")}
            value={t(`operator.authMode.${config.authMode}.label`)}
          />
          {config.version != null && (
            <Row label={t("operator.status.version", "Version")} value={String(config.version)} />
          )}
        </div>

        <DeploymentBadge state={state} loading={statusLoading} />

        {state === "ERROR" && (
          <div
            className="flex items-start gap-2 rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm text-destructive"
            data-testid="operator-deployment-error"
          >
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
            <span>{t("operator.status.errorHelp", "The operator agent failed to deploy. Reconfigure it to try again, or check the platform logs for the underlying error.")}</span>
          </div>
        )}

        {/* Nothing depends on this today — read_only never offers a write tool
            regardless — but it is checked and shown on every load so the fact is
            never assumed the one time it starts to matter (read_write scope). */}
        {gate && !gate.verified && (
          <div
            className="flex items-start gap-2 rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm text-destructive"
            data-testid="operator-gate-unverified"
          >
            <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0" />
            <span>
              {t("operator.status.gateUnverifiedHelp", "The approval gate could not be verified on this agent's document.")}
              {gate.reason ? ` (${gate.reason})` : ""}
            </span>
          </div>
        )}

        <div className="space-y-2 pt-2">
          <Button
            variant="outline"
            className="w-full gap-2"
            onClick={onRecheck}
            disabled={busy || recheckPending}
            data-testid="operator-connection-check"
          >
            {recheckPending ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <PlugZap className="h-4 w-4" />
            )}
            {t("operator.status.connectionCheck", "Check connection")}
          </Button>
          <Button
            variant="outline"
            className="w-full gap-2"
            onClick={onReconfigure}
            disabled={busy}
          >
            <RefreshCw className="h-4 w-4" />
            {t("operator.status.reconfigure", "Reconfigure")}
          </Button>
          <Button
            variant="destructive"
            className="w-full gap-2"
            onClick={() => setConfirmDeactivate(true)}
            disabled={busy}
            data-testid="operator-kill-switch"
          >
            <Power className="h-4 w-4" />
            {t("operator.status.deactivate", "Deactivate")}
          </Button>
          <Button
            variant="ghost"
            className="w-full gap-2 text-muted-foreground"
            onClick={() => setConfirmReset(true)}
            disabled={busy}
            data-testid="operator-reset"
          >
            <Trash2 className="h-4 w-4" />
            {t("operator.status.reset", "Delete operator")}
          </Button>
        </div>
      </CardContent>

      <AlertDialog
        open={confirmDeactivate}
        onOpenChange={setConfirmDeactivate}
        title={t("operator.status.deactivateConfirmTitle", "Deactivate the Platform Operator?")}
        description={t("operator.status.deactivateConfirmBody", "The operator is undeployed and stops responding. Its configuration is kept, so you can turn it back on later.")}
        confirmLabel={t("operator.status.deactivate", "Deactivate")}
        cancelLabel={t("common.cancel", "Cancel")}
        variant="destructive"
        onConfirm={() => {
          setConfirmDeactivate(false);
          onDeactivate();
        }}
      />
      <AlertDialog
        open={confirmReset}
        onOpenChange={setConfirmReset}
        title={t("operator.status.resetConfirmTitle", "Delete the Platform Operator?")}
        description={t("operator.status.resetConfirmBody", "The operator agent and all the resources created for it are permanently deleted, along with its saved configuration. This cannot be undone.")}
        confirmLabel={t("operator.status.reset", "Delete operator")}
        cancelLabel={t("common.cancel", "Cancel")}
        variant="destructive"
        onConfirm={() => {
          setConfirmReset(false);
          onReset();
        }}
      />
    </Card>
  );
}

/**
 * Shows whether the tool-approval gate is currently verified on the live agent
 * document. Independent of the deployment-status badge above: a `READY`
 * deployment says nothing about whether `hitlConfig` survived version skew or
 * was flipped off deployment-wide — this badge is the one thing that does.
 */
function GateBadge({
  gate,
  loading,
}: {
  gate: GateVerificationResult | null | undefined;
  loading: boolean;
}) {
  const { t } = useTranslation();
  if (loading && !gate) {
    return (
      <Badge variant="secondary" className="gap-1" data-testid="operator-gate-checking">
        <Loader2 className="h-3 w-3 animate-spin" />
        {t("operator.status.gateChecking", "Verifying gate…")}
      </Badge>
    );
  }
  if (!gate) {
    return (
      <Badge variant="outline" className="gap-1" data-testid="operator-gate-unknown">
        <ShieldQuestion className="h-3 w-3" />
        {t("operator.status.gateUnknown", "Gate not checked")}
      </Badge>
    );
  }
  if (gate.verified) {
    return (
      <Badge variant="success" className="gap-1" data-testid="operator-gate-verified">
        <ShieldCheck className="h-3 w-3" />
        {t("operator.status.gateVerified", "Gate verified")}
      </Badge>
    );
  }
  return (
    <Badge variant="destructive" className="gap-1" data-testid="operator-gate-badge-unverified">
      <ShieldAlert className="h-3 w-3" />
      {t("operator.status.gateNotVerified", "Gate not verified")}
    </Badge>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-3">
      <span className="text-muted-foreground">{label}</span>
      <span className="font-medium">{value}</span>
    </div>
  );
}

function DeploymentBadge({
  state,
  loading,
}: {
  state: DeploymentStatus["status"] | undefined;
  loading: boolean;
}) {
  const { t } = useTranslation();
  if (loading && !state) {
    return (
      <Badge variant="secondary" className="gap-1">
        <Loader2 className="h-3 w-3 animate-spin" />
        {t("operator.status.checking", "Checking…")}
      </Badge>
    );
  }
  switch (state) {
    case "READY":
      return (
        <Badge variant="success" className="gap-1" data-testid="operator-status-ready">
          <CheckCircle2 className="h-3 w-3" />
          {t("operator.status.ready", "Deployed")}
        </Badge>
      );
    case "IN_PROGRESS":
      return (
        <Badge variant="warning" className="gap-1">
          <Loader2 className="h-3 w-3 animate-spin" />
          {t("operator.status.inProgress", "Deploying…")}
        </Badge>
      );
    case "ERROR":
      return (
        <Badge variant="destructive" className="gap-1">
          <AlertTriangle className="h-3 w-3" />
          {t("operator.status.error", "Deployment error")}
        </Badge>
      );
    default:
      return (
        <Badge variant="outline" className="gap-1">
          {t("operator.status.notFound", "Not deployed")}
        </Badge>
      );
  }
}
