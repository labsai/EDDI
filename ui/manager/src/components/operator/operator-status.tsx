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
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { AlertDialog } from "@/components/ui/alert-dialog";
import type { OperatorConfig } from "@/lib/api/operator";
import type { DeploymentStatus } from "@/lib/api/agents";

interface OperatorStatusPanelProps {
  config: OperatorConfig;
  status: DeploymentStatus | null | undefined;
  statusLoading: boolean;
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
        <CardTitle className="text-base">{t("operator.status.title")}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="flex flex-wrap gap-2">
          <Badge variant="secondary" className="gap-1">
            <Cpu className="h-3 w-3" />
            {config.model}
          </Badge>
          <Badge variant="success" className="gap-1">
            <Lock className="h-3 w-3" />
            {t("operator.readOnlyChip")}
          </Badge>
        </div>

        <div className="space-y-1 text-sm">
          <Row label={t("operator.status.provider")} value={config.provider} />
          <Row label={t("operator.status.environment")} value={config.environment} />
          <Row
            label={t("operator.status.authMode")}
            value={t(`operator.authMode.${config.authMode}.label`)}
          />
          {config.version != null && (
            <Row label={t("operator.status.version")} value={String(config.version)} />
          )}
        </div>

        <DeploymentBadge state={state} loading={statusLoading} />

        {state === "ERROR" && (
          <div
            className="flex items-start gap-2 rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm text-destructive"
            data-testid="operator-deployment-error"
          >
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
            <span>{t("operator.status.errorHelp")}</span>
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
            {t("operator.status.connectionCheck")}
          </Button>
          <Button
            variant="outline"
            className="w-full gap-2"
            onClick={onReconfigure}
            disabled={busy}
          >
            <RefreshCw className="h-4 w-4" />
            {t("operator.status.reconfigure")}
          </Button>
          <Button
            variant="destructive"
            className="w-full gap-2"
            onClick={() => setConfirmDeactivate(true)}
            disabled={busy}
            data-testid="operator-kill-switch"
          >
            <Power className="h-4 w-4" />
            {t("operator.status.deactivate")}
          </Button>
          <Button
            variant="ghost"
            className="w-full gap-2 text-muted-foreground"
            onClick={() => setConfirmReset(true)}
            disabled={busy}
            data-testid="operator-reset"
          >
            <Trash2 className="h-4 w-4" />
            {t("operator.status.reset")}
          </Button>
        </div>
      </CardContent>

      <AlertDialog
        open={confirmDeactivate}
        onOpenChange={setConfirmDeactivate}
        title={t("operator.status.deactivateConfirmTitle")}
        description={t("operator.status.deactivateConfirmBody")}
        confirmLabel={t("operator.status.deactivate")}
        cancelLabel={t("common.cancel")}
        variant="destructive"
        onConfirm={() => {
          setConfirmDeactivate(false);
          onDeactivate();
        }}
      />
      <AlertDialog
        open={confirmReset}
        onOpenChange={setConfirmReset}
        title={t("operator.status.resetConfirmTitle")}
        description={t("operator.status.resetConfirmBody")}
        confirmLabel={t("operator.status.reset")}
        cancelLabel={t("common.cancel")}
        variant="destructive"
        onConfirm={() => {
          setConfirmReset(false);
          onReset();
        }}
      />
    </Card>
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
        {t("operator.status.checking")}
      </Badge>
    );
  }
  switch (state) {
    case "READY":
      return (
        <Badge variant="success" className="gap-1" data-testid="operator-status-ready">
          <CheckCircle2 className="h-3 w-3" />
          {t("operator.status.ready")}
        </Badge>
      );
    case "IN_PROGRESS":
      return (
        <Badge variant="warning" className="gap-1">
          <Loader2 className="h-3 w-3 animate-spin" />
          {t("operator.status.inProgress")}
        </Badge>
      );
    case "ERROR":
      return (
        <Badge variant="destructive" className="gap-1">
          <AlertTriangle className="h-3 w-3" />
          {t("operator.status.error")}
        </Badge>
      );
    default:
      return (
        <Badge variant="outline" className="gap-1">
          {t("operator.status.notFound")}
        </Badge>
      );
  }
}
