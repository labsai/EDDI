import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Save, X, RefreshCw } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { useUpdateGroup } from "@/hooks/use-groups";
import type { AgentGroupConfiguration } from "@/lib/api/groups";
import type { GroupHitlConfig } from "@/lib/api/hitl";
import {
  getStylePhases,
  applyApprovalPhases,
  DEFAULT_GROUP_HITL_CONFIG,
  requiresApprovalTimeout,
  isValidIsoDuration,
} from "@/lib/hitl-config";

/**
 * Inline editor for a group's Human-in-the-Loop approval settings — the config
 * that could previously only be set in the create wizard. Edits `hitlConfig`
 * (timeout policy/duration, granularity, on-rejection) and per-phase
 * `requiresApproval`, then saves via {@link useUpdateGroup}.
 *
 * Preset-style groups store `phases: null` (the engine expands them at runtime);
 * to let a user mark which phases gate approval we materialize the phase list on
 * save — the same behavior-preserving expansion the wizard uses.
 */
export function GroupHitlEditor({
  config,
  groupId,
  groupVersion,
  onDone,
}: {
  config: AgentGroupConfiguration;
  groupId: string;
  groupVersion: number;
  onDone: () => void;
}) {
  const { t } = useTranslation();
  const update = useUpdateGroup();

  const basePhases = config.phases ?? getStylePhases(config.style, config.maxRounds);
  const isTaskForce = config.style === "TASK_FORCE";

  const [enabled, setEnabled] = useState(
    !!config.hitlConfig || basePhases.some((p) => p.requiresApproval),
  );
  const [hitl, setHitl] = useState<GroupHitlConfig>(
    config.hitlConfig ?? DEFAULT_GROUP_HITL_CONFIG,
  );
  const [approvalPhases, setApprovalPhases] = useState<Set<string>>(
    new Set(basePhases.filter((p) => p.requiresApproval).map((p) => p.name)),
  );
  const [timeoutDraft, setTimeoutDraft] = useState(config.hitlConfig?.approvalTimeout ?? "");

  const finite = requiresApprovalTimeout(hitl.timeoutPolicy);
  const timeoutInvalid =
    enabled && finite && (!timeoutDraft.trim() || !isValidIsoDuration(timeoutDraft.trim()));
  // Enabling approval without gating any phase would save a hitlConfig that
  // looks configured but never pauses (phase.requiresApproval is the sole group
  // pause trigger), so block save until at least one phase is selected.
  const noPhaseSelected =
    enabled && basePhases.length > 0 && approvalPhases.size === 0;

  const togglePhase = (name: string) =>
    setApprovalPhases((prev) => {
      const next = new Set(prev);
      if (next.has(name)) next.delete(name);
      else next.add(name);
      return next;
    });

  const patchHitl = (u: Partial<GroupHitlConfig>) => setHitl((h) => ({ ...h, ...u }));

  const save = () => {
    if (timeoutInvalid || noPhaseSelected) return;
    let phases = config.phases;
    let hitlConfig: GroupHitlConfig | undefined;
    if (enabled) {
      phases = applyApprovalPhases(basePhases, [...approvalPhases]);
      hitlConfig = {
        approvalTimeout: timeoutDraft.trim() || null,
        timeoutPolicy: hitl.timeoutPolicy,
        // TASK granularity only applies to TASK_FORCE (the only EXECUTE style).
        granularity: isTaskForce ? hitl.granularity : "PHASE",
        onTaskRejection: hitl.onTaskRejection,
      };
    } else {
      // Disable: clear every approval gate and drop the hitl block.
      phases = config.phases
        ? config.phases.map((p) => ({ ...p, requiresApproval: false }))
        : null;
      hitlConfig = undefined;
    }
    const next: AgentGroupConfiguration = { ...config, phases, hitlConfig };
    update.mutate(
      { id: groupId, version: groupVersion, config: next },
      {
        onSuccess: () => {
          toast.success(t("groups.hitlSaved", "Approval settings saved"));
          onDone();
        },
        onError: () => toast.error(t("common.error", "Something went wrong")),
      },
    );
  };

  const inputCls =
    "h-8 w-full rounded-md border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring";

  return (
    <div className="rounded-lg border border-amber-500/30 bg-amber-500/5 p-2.5 space-y-2.5" data-testid="group-hitl-editor">
      <label className="flex items-center gap-2 text-xs font-medium text-foreground">
        <input
          type="checkbox"
          checked={enabled}
          onChange={(e) => setEnabled(e.target.checked)}
          className="h-3.5 w-3.5 rounded border-input accent-primary"
          data-testid="group-hitl-enable"
        />
        {t("groups.hitlEnable", "Require human approval")}
      </label>

      {enabled && (
        <div className="space-y-2.5">
          {/* Approval phases */}
          {basePhases.length > 0 && (
            <div>
              <p className="mb-1 text-[10px] text-muted-foreground">
                {t("groups.hitlApprovalPhases", "Phases requiring approval")}
              </p>
              <div className="space-y-1">
                {basePhases.map((p) => (
                  <label key={p.name} className="flex items-center gap-2 text-xs text-foreground">
                    <input
                      type="checkbox"
                      checked={approvalPhases.has(p.name)}
                      onChange={() => togglePhase(p.name)}
                      className="h-3 w-3 rounded border-input accent-primary"
                      data-testid={`group-hitl-phase-${p.name}`}
                    />
                    {p.name}
                  </label>
                ))}
              </div>
              {noPhaseSelected && (
                <p className="mt-1 text-[10px] text-destructive" data-testid="group-hitl-no-phase">
                  {t("groups.hitlSelectPhase", "Select at least one phase to require approval.")}
                </p>
              )}
            </div>
          )}

          {/* Timeout policy */}
          <div>
            <p className="mb-1 text-[10px] text-muted-foreground">{t("hitl.timeoutPolicy", "Timeout")}</p>
            <select
              value={hitl.timeoutPolicy ?? "WAIT_INDEFINITELY"}
              onChange={(e) => {
                const policy = e.target.value as GroupHitlConfig["timeoutPolicy"];
                patchHitl({ timeoutPolicy: policy });
                if (
                  requiresApprovalTimeout(policy) &&
                  !(timeoutDraft && isValidIsoDuration(timeoutDraft))
                ) {
                  setTimeoutDraft("PT15M");
                }
              }}
              className={inputCls}
              data-testid="group-hitl-timeout-policy"
            >
              <option value="WAIT_INDEFINITELY">{t("hitl.timeoutWaitIndefinitely", "Wait Indefinitely")}</option>
              <option value="AUTO_APPROVE">{t("hitl.timeoutAutoApprove", "Auto-Approve")}</option>
              <option value="AUTO_REJECT">{t("hitl.timeoutAutoReject", "Auto-Reject")}</option>
              <option value="ABORT">{t("hitl.timeoutAbort", "Abort")}</option>
            </select>
          </div>

          {/* Approval timeout (finite policies) */}
          {finite && (
            <div>
              <p className="mb-1 text-[10px] text-muted-foreground">{t("agentDetail.hitlApprovalTimeout", "Approval timeout (ISO-8601)")}</p>
              <input
                type="text"
                value={timeoutDraft}
                onChange={(e) => setTimeoutDraft(e.target.value)}
                placeholder="PT15M"
                className={`${inputCls} font-mono ${timeoutInvalid ? "border-destructive" : ""}`}
                data-testid="group-hitl-approval-timeout"
              />
              {timeoutInvalid && (
                <p className="mt-1 text-[10px] text-destructive">
                  {t("agentDetail.hitlTimeoutInvalid", "A finite policy needs a positive ISO-8601 duration, e.g. PT15M.")}
                </p>
              )}
            </div>
          )}

          {/* Granularity + on-rejection (TASK_FORCE only) */}
          {isTaskForce && (
            <>
              <div>
                <p className="mb-1 text-[10px] text-muted-foreground">{t("hitl.granularity", "Granularity")}</p>
                <select
                  value={hitl.granularity ?? "PHASE"}
                  onChange={(e) => patchHitl({ granularity: e.target.value as GroupHitlConfig["granularity"] })}
                  className={inputCls}
                  data-testid="group-hitl-granularity"
                >
                  <option value="PHASE">{t("hitl.granularityPhase", "Phase")}</option>
                  <option value="TASK">{t("hitl.granularityTask", "Task")}</option>
                </select>
              </div>
              {hitl.granularity === "TASK" && (
                <div>
                  <p className="mb-1 text-[10px] text-muted-foreground">{t("groups.hitlOnTaskRejection", "On rejection")}</p>
                  <select
                    value={hitl.onTaskRejection ?? "FAIL"}
                    onChange={(e) => patchHitl({ onTaskRejection: e.target.value as GroupHitlConfig["onTaskRejection"] })}
                    className={inputCls}
                    data-testid="group-hitl-on-rejection"
                  >
                    <option value="FAIL">{t("hitl.rejectionFail", "Fail the task")}</option>
                    <option value="RETRY">{t("hitl.rejectionRetry", "Retry the task")}</option>
                  </select>
                </div>
              )}
            </>
          )}
        </div>
      )}

      <div className="flex gap-2 pt-1">
        <Button
          size="sm"
          className="h-7 flex-1 text-xs"
          onClick={save}
          disabled={update.isPending || timeoutInvalid || noPhaseSelected}
          data-testid="group-hitl-save"
        >
          {update.isPending ? <RefreshCw className="h-3 w-3 animate-spin me-1" /> : <Save className="h-3 w-3 me-1" />}
          {t("common.save", "Save")}
        </Button>
        <Button
          variant="outline"
          size="sm"
          className="h-7 text-xs"
          onClick={onDone}
          disabled={update.isPending}
          data-testid="group-hitl-cancel"
        >
          <X className="h-3 w-3 me-1" />
          {t("common.cancel", "Cancel")}
        </Button>
      </div>
    </div>
  );
}
