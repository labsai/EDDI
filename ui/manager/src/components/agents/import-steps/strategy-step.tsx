import { useTranslation } from "react-i18next";
import {
  FileArchive,
  Plus,
  RefreshCw,
  Upload,
  Globe,
  ArrowLeft,
  ArrowRight,
} from "lucide-react";
import { Button } from "@/components/ui/button";

type Strategy = "create" | "merge" | "upgrade" | "sync";

interface StrategyStepProps {
  file: File;
  strategy: Strategy;
  onStrategyChange: (s: Strategy) => void;
  onBack: () => void;
  onNext: () => void;
  isLoading: boolean;
  isPreviewing: boolean;
  error: string | null;
}

export function StrategyStep({
  file,
  strategy,
  onStrategyChange,
  onBack,
  onNext,
  isLoading,
  isPreviewing,
  error,
}: StrategyStepProps) {
  const { t } = useTranslation();

  return (
    <div className="flex-1 space-y-4">
      <div className="flex items-center gap-3 rounded-lg bg-secondary/50 p-3">
        <FileArchive className="h-5 w-5 text-primary shrink-0" />
        <div className="min-w-0">
          <p className="text-sm font-medium text-foreground truncate">{file.name}</p>
          <p className="text-xs text-muted-foreground">
            {(file.size / 1024).toFixed(1)} KB
          </p>
        </div>
      </div>

      <fieldset className="space-y-2">
        <legend className="text-sm font-medium text-foreground mb-2">
          {t("importDialog.strategyLabel", "Import Strategy")}
        </legend>

        <StrategyOption
          value="create"
          current={strategy}
          onChange={onStrategyChange}
          icon={<Plus className="h-4 w-4 text-emerald-500" />}
          label={t("importDialog.createNew", "Create as new agent")}
          desc={t("importDialog.createNewDesc", "Creates a fresh copy with new IDs. Best for first-time import.")}
          testId="strategy-create"
        />

        <StrategyOption
          value="merge"
          current={strategy}
          onChange={onStrategyChange}
          icon={<RefreshCw className="h-4 w-4 text-blue-500" />}
          label={t("importDialog.mergeSync", "Merge / sync with existing")}
          desc={t("importDialog.mergeSyncDesc", "Updates existing resources if previously imported. Creates new ones if not found.")}
          testId="strategy-merge"
        />

        <StrategyOption
          value="upgrade"
          current={strategy}
          onChange={onStrategyChange}
          icon={<Upload className="h-4 w-4 text-amber-500" />}
          label={t("importDialog.upgradeExisting", "Upgrade existing agent")}
          desc={t("importDialog.upgradeDesc", "Match resources structurally against a target agent. Best for promoting across environments.")}
          testId="strategy-upgrade"
        />

        <StrategyOption
          value="sync"
          current={strategy}
          onChange={onStrategyChange}
          icon={<Globe className="h-4 w-4 text-violet-500" />}
          label={t("importDialog.syncRemote", "Sync from remote instance")}
          desc={t("importDialog.syncDesc", "Connect to another EDDI instance and sync a specific agent live.")}
          testId="strategy-sync"
        />
      </fieldset>

      {error && <p className="text-sm text-destructive">{error}</p>}

      <div className="flex justify-between pt-2">
        <Button variant="ghost" onClick={onBack} disabled={isLoading}>
          <ArrowLeft className="h-4 w-4" />
          {t("common.back", "Back")}
        </Button>
        <Button onClick={onNext} disabled={isLoading} data-testid="import-confirm-strategy">
          {isPreviewing
            ? t("common.loading", "Loading...")
            : strategy === "create"
              ? t("importDialog.importNow", "Import Now")
              : strategy === "merge"
                ? t("importDialog.previewChanges", "Preview Changes")
                : t("common.next", "Next")}
          <ArrowRight className="h-4 w-4" />
        </Button>
      </div>
    </div>
  );
}

/* ─── Strategy radio option ─── */

function StrategyOption({
  value,
  current,
  onChange,
  icon,
  label,
  desc,
  testId,
}: {
  value: Strategy;
  current: Strategy;
  onChange: (s: Strategy) => void;
  icon: React.ReactNode;
  label: string;
  desc: string;
  testId: string;
}) {
  return (
    <label
      className={`flex items-start gap-3 rounded-lg border p-3 cursor-pointer transition-colors ${
        current === value
          ? "border-primary bg-primary/5"
          : "border-border hover:border-primary/30"
      }`}
      data-testid={testId}
    >
      <input
        type="radio"
        name="strategy"
        value={value}
        checked={current === value}
        onChange={() => onChange(value)}
        className="mt-0.5 accent-primary"
      />
      <div>
        <div className="flex items-center gap-1.5">
          {icon}
          <span className="text-sm font-medium text-foreground">{label}</span>
        </div>
        <p className="mt-0.5 text-xs text-muted-foreground">{desc}</p>
      </div>
    </label>
  );
}
