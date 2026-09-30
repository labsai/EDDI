import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { ArrowUpCircle, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { AlertDialog } from "@/components/ui/alert-dialog";
import type {
  InstructionsChoice,
  OperatorUpgradeAssessment,
} from "@/lib/operator/operator-revision";

interface OperatorUpgradeNoticeProps {
  assessment: OperatorUpgradeAssessment;
  /** Run the one-click upgrade with the admin's choice for the instructions. */
  onUpgrade: (choice: InstructionsChoice) => void;
  /** Open the full activation form — used when the upgrade cannot run in one click. */
  onOpenForm: () => void;
  /** True while an upgrade (an activation) is running. */
  busy: boolean;
  /** Translated label of the running activation stage, shown while busy. */
  stageLabel?: string;
  /** The last upgrade's failure, if any. */
  error?: string | null;
}

/**
 * The operator page's "update available" banner and its confirmation.
 *
 * Says what changes before asking — which tools are added or removed, and what
 * happens to the instructions — because an upgrade is a replacement: a new
 * operator is built and verified, then the old one is removed, and the admin's
 * open operator chat ends with it. None of that should be a surprise.
 */
export function OperatorUpgradeNotice({
  assessment,
  onUpgrade,
  onOpenForm,
  busy,
  stageLabel,
  error,
}: OperatorUpgradeNoticeProps) {
  const { t } = useTranslation();
  const [confirmOpen, setConfirmOpen] = useState(false);
  // Pre-selected by what is known: an untouched default is replaced, a known
  // edit is kept. An `unknown` body (a config from before this was tracked) is
  // most often an OLD default — admins rarely edit it — so the new default is
  // offered first, with the choice in plain view.
  const [choice, setChoice] = useState<InstructionsChoice>(
    assessment.instructions === "customized" ? "keep-current" : "use-new-default",
  );

  const added = assessment.addedEndpoints;
  const removed = assessment.removedEndpoints;
  const blocked = assessment.blocker !== null;

  return (
    <div
      className="flex flex-wrap items-start gap-3 rounded-md border border-primary/40 bg-primary/5 p-3 text-sm"
      role="status"
      data-testid="operator-upgrade-notice"
    >
      <ArrowUpCircle className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
      <div className="min-w-0 flex-1 space-y-1">
        <p className="font-medium">
          {t("operator.upgrade.title", "An update for the Platform Operator is available")}
        </p>
        <p className="text-muted-foreground">
          {t(
            "operator.upgrade.description",
            "It was set up by an earlier version of the Manager (revision {{from}}; this version ships {{to}}), so its instructions and tools are out of date.",
            { from: assessment.provisionedRevision, to: assessment.currentRevision },
          )}
        </p>
        {added && added.length > 0 && (
          <details data-testid="operator-upgrade-added">
            <summary className="cursor-pointer text-muted-foreground">
              {t("operator.upgrade.addedTools", "{{count}} new tools", { count: added.length })}
            </summary>
            <ul className="mt-1 space-y-0.5 font-mono text-xs text-muted-foreground">
              {added.map((e) => (
                <li key={e}>{e}</li>
              ))}
            </ul>
          </details>
        )}
        {removed && removed.length > 0 && (
          <details data-testid="operator-upgrade-removed">
            <summary className="cursor-pointer text-muted-foreground">
              {t("operator.upgrade.removedTools", "{{count}} tools removed", { count: removed.length })}
            </summary>
            <ul className="mt-1 space-y-0.5 font-mono text-xs text-muted-foreground">
              {removed.map((e) => (
                <li key={e}>{e}</li>
              ))}
            </ul>
          </details>
        )}
        {blocked && (
          <p className="text-muted-foreground" data-testid="operator-upgrade-blocker">
            {assessment.blocker === "credential"
              ? t(
                  "operator.upgrade.blockerCredential",
                  "Its model key was entered as plain text, which is never stored — enter it again to upgrade.",
                )
              : assessment.blocker === "llmBaseUrl"
                ? t(
                    "operator.upgrade.blockerBaseUrl",
                    "Its model server address was not recorded — enter it again to upgrade.",
                  )
                : t(
                    "operator.upgrade.blockerProvider",
                    "Its model provider can no longer be set up this way — choose another to upgrade.",
                  )}
          </p>
        )}
        {busy && stageLabel && (
          <p className="flex items-center gap-2 text-muted-foreground" data-testid="operator-upgrade-progress">
            <Loader2 className="h-3 w-3 animate-spin" />
            {stageLabel}
          </p>
        )}
        {error && (
          <p className="text-destructive" role="alert" data-testid="operator-upgrade-error">
            {error}
          </p>
        )}
      </div>
      <Button
        size="sm"
        onClick={() => (blocked ? onOpenForm() : setConfirmOpen(true))}
        disabled={busy}
        data-testid="operator-upgrade-start"
      >
        {busy && <Loader2 className="me-2 h-3 w-3 animate-spin" />}
        {blocked
          ? t("operator.upgrade.reviewAction", "Review and upgrade")
          : t("operator.upgrade.action", "Upgrade")}
      </Button>

      <AlertDialog
        open={confirmOpen}
        onOpenChange={setConfirmOpen}
        variant="warning"
        title={t("operator.upgrade.confirmTitle", "Upgrade the Platform Operator?")}
        description={t(
          "operator.upgrade.confirmBody",
          "A new operator is built with the current instructions and tools and checked like a new activation; only then is the old one removed. Model, key, environment and access settings are kept. The open operator chat ends, and conversations with the old operator no longer appear in the history.",
        )}
        confirmLabel={t("operator.upgrade.confirm", "Upgrade now")}
        cancelLabel={t("common.cancel", "Cancel")}
        onConfirm={() => {
          setConfirmOpen(false);
          onUpgrade(choice);
        }}
      >
        {assessment.instructions !== "default" && (
          <fieldset className="space-y-2 text-sm" data-testid="operator-upgrade-instructions">
            <legend className="mb-1 font-medium">
              {assessment.instructions === "customized"
                ? t("operator.upgrade.instructionsCustomized", "You edited the operator's instructions.")
                : t(
                    "operator.upgrade.instructionsUnknown",
                    "The operator's instructions differ from the current default — either an older default or your own edits.",
                  )}
            </legend>
            <label className="flex items-start gap-2">
              <input
                type="radio"
                name="operator-upgrade-instructions"
                checked={choice === "use-new-default"}
                onChange={() => setChoice("use-new-default")}
                data-testid="operator-upgrade-use-default"
              />
              <span>{t("operator.upgrade.useNewDefault", "Replace them with the new default")}</span>
            </label>
            <label className="flex items-start gap-2">
              <input
                type="radio"
                name="operator-upgrade-instructions"
                checked={choice === "keep-current"}
                onChange={() => setChoice("keep-current")}
                data-testid="operator-upgrade-keep-current"
              />
              <span>
                {t(
                  "operator.upgrade.keepCurrent",
                  "Keep the current text (it will not learn what the new default adds)",
                )}
              </span>
            </label>
          </fieldset>
        )}
      </AlertDialog>
    </div>
  );
}

/**
 * The compact form of the notice, for surfaces that are not the operator page
 * (the docked drawer, the dashboard): one line and a link to where the upgrade
 * is done. Upgrading from a 380-pixel drawer while chatting with the very agent
 * being replaced would be the wrong place to do it.
 */
export function OperatorUpgradeHint({ testId }: { testId?: string }) {
  const { t } = useTranslation();
  return (
    <div
      className="flex items-center gap-2 rounded-md border border-primary/40 bg-primary/5 px-3 py-2 text-xs"
      role="status"
      data-testid={testId ?? "operator-upgrade-hint"}
    >
      <ArrowUpCircle className="h-3.5 w-3.5 shrink-0 text-primary" />
      <span className="flex-1">
        {t("operator.upgrade.hint", "An update for the Platform Operator is available.")}
      </span>
      <Link to="/manage/operator" className="font-medium text-primary hover:underline">
        {t("operator.upgrade.hintAction", "Upgrade")}
      </Link>
    </div>
  );
}
