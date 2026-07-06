import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { AlertTriangle } from "lucide-react";
import {
  validateToolApprovals,
  toolApprovalsInheritsAutoApprove,
  KNOWN_TOOL_SOURCES,
} from "@/lib/hitl-tool-approvals";
import { requiresApprovalTimeout, isValidIsoDuration } from "@/lib/hitl-config";
import { noProgressPolicyLabel } from "@/lib/hitl-labels";
import {
  MAX_PAUSE_REASON_LENGTH,
  type ToolApprovalsConfig,
  type HitlTimeoutPolicy,
  type HitlOnNoProgress,
} from "@/lib/api/hitl";

/**
 * Editor for a {@link ToolApprovalsConfig} (tool-level approval gating). Shared
 * by the agent-level default and the per-LLM-task override so the client-side
 * validation (which mirrors the backend save-time rules) lives in one place.
 *
 * `agentTimeoutPolicy` drives the AUTO_APPROVE-demotion warning (agent-level
 * only); pass it undefined for the per-task editor.
 */
export function ToolApprovalsEditor({
  value,
  disabled,
  agentTimeoutPolicy,
  onChange,
  idPrefix = "hitl-tool",
}: {
  value: ToolApprovalsConfig;
  disabled?: boolean;
  agentTimeoutPolicy?: string | null;
  onChange: (updates: Partial<ToolApprovalsConfig>) => void;
  idPrefix?: string;
}) {
  const { t } = useTranslation();

  const [requireText, setRequireText] = useState((value.requireApproval ?? []).join("\n"));
  const [exemptText, setExemptText] = useState((value.exempt ?? []).join("\n"));
  const [reason, setReason] = useState(value.pauseReason ?? "");
  const [pending, setPending] = useState(value.pendingMessage ?? "");
  const [timeoutDraft, setTimeoutDraft] = useState(value.approvalTimeout ?? "");
  const [maxPausesDraft, setMaxPausesDraft] = useState(
    value.maxPausesPerTurn != null ? String(value.maxPausesPerTurn) : "",
  );
  const [maxAutoDraft, setMaxAutoDraft] = useState(
    value.maxAutoApprovalsPerTurn != null ? String(value.maxAutoApprovalsPerTurn) : "",
  );

  const finite = requiresApprovalTimeout(value.timeoutPolicy);

  // Resync local drafts when the committed value changes (e.g. after save).
  useEffect(() => setRequireText((value.requireApproval ?? []).join("\n")), [value.requireApproval]);
  useEffect(() => setExemptText((value.exempt ?? []).join("\n")), [value.exempt]);
  useEffect(() => setReason(value.pauseReason ?? ""), [value.pauseReason]);
  useEffect(() => setPending(value.pendingMessage ?? ""), [value.pendingMessage]);
  // Include `finite` so toggling the timeout policy off and back on re-seeds the
  // draft from the committed value, discarding a stale unsaved (possibly invalid)
  // draft that would otherwise linger in the re-shown input.
  useEffect(() => setTimeoutDraft(value.approvalTimeout ?? ""), [value.approvalTimeout, finite]);
  useEffect(
    () => setMaxPausesDraft(value.maxPausesPerTurn != null ? String(value.maxPausesPerTurn) : ""),
    [value.maxPausesPerTurn],
  );
  useEffect(
    () => setMaxAutoDraft(value.maxAutoApprovalsPerTurn != null ? String(value.maxAutoApprovalsPerTurn) : ""),
    [value.maxAutoApprovalsPerTurn],
  );

  const parseList = (s: string): string[] =>
    s.split("\n").map((x) => x.trim()).filter((x) => x.length > 0);

  const numberOrNull = (raw: string): number | null => {
    if (raw.trim() === "") return null;
    const n = Number(raw);
    return Number.isFinite(n) ? n : null;
  };

  // Validate the LIVE (draft) config so errors reflect what is being typed.
  const preview: ToolApprovalsConfig = {
    ...value,
    requireApproval: parseList(requireText),
    exempt: parseList(exemptText),
    pauseReason: reason.trim() || null,
    pendingMessage: pending.trim() || null,
    approvalTimeout: timeoutDraft.trim() || null,
    maxPausesPerTurn: numberOrNull(maxPausesDraft),
    maxAutoApprovalsPerTurn: numberOrNull(maxAutoDraft),
  };
  const errors = validateToolApprovals(preview);

  const showDemotionWarn = toolApprovalsInheritsAutoApprove(agentTimeoutPolicy, value);

  const errText = (msg?: string) =>
    msg ? (
      <p className="mt-1 text-[10px] text-destructive">{msg}</p>
    ) : null;

  return (
    <div className="space-y-3" data-testid="tool-approvals-editor">
      <p className="text-[10px] text-muted-foreground leading-relaxed">
        {t(
          "agentDetail.toolApprovalsDesc",
          "Pause when the model invokes a matching tool, before it runs. An empty \"require approval\" list disables tool gating.",
        )}
      </p>

      {/* requireApproval */}
      <div>
        <label className="mb-1 block text-[10px] text-muted-foreground">
          {t("agentDetail.toolRequireApproval", "Require approval (glob patterns, one per line)")}
        </label>
        <textarea
          value={requireText}
          onChange={(e) => setRequireText(e.target.value)}
          onBlur={() => onChange({ requireApproval: parseList(requireText) })}
          disabled={disabled}
          rows={3}
          placeholder={"mcp:*\ndelete_*\ntransfer_funds"}
          className={`w-full resize-none rounded-md border bg-background px-2 py-1.5 font-mono text-[11px] text-foreground focus:outline-none focus:ring-1 focus:ring-ring ${errors.requireApproval ? "border-destructive" : "border-input"}`}
          data-testid={`${idPrefix}-require`}
        />
        {errText(errors.requireApproval)}
        <p className="mt-1 text-[10px] text-muted-foreground">
          {t("agentDetail.toolPatternHint", "'*' is the only wildcard. Optional source prefix:")}{" "}
          <span className="font-mono">{KNOWN_TOOL_SOURCES.join(", ")}</span>
        </p>
      </div>

      {/* exempt */}
      <div>
        <label className="mb-1 block text-[10px] text-muted-foreground">
          {t("agentDetail.toolExempt", "Exempt (always allowed, one per line)")}
        </label>
        <textarea
          value={exemptText}
          onChange={(e) => setExemptText(e.target.value)}
          onBlur={() => onChange({ exempt: parseList(exemptText) })}
          disabled={disabled}
          rows={2}
          placeholder={"mcp:read_*"}
          className={`w-full resize-none rounded-md border bg-background px-2 py-1.5 font-mono text-[11px] text-foreground focus:outline-none focus:ring-1 focus:ring-ring ${errors.exempt ? "border-destructive" : "border-input"}`}
          data-testid={`${idPrefix}-exempt`}
        />
        {errText(errors.exempt)}
      </div>

      {/* caps */}
      <div className="grid grid-cols-2 gap-2">
        <div>
          <label className="mb-1 block text-[10px] text-muted-foreground">
            {t("agentDetail.toolMaxPauses", "Max pauses / turn (1–10)")}
          </label>
          <input
            type="number"
            min={1}
            max={10}
            value={maxPausesDraft}
            onChange={(e) => setMaxPausesDraft(e.target.value)}
            onBlur={() => onChange({ maxPausesPerTurn: numberOrNull(maxPausesDraft) })}
            disabled={disabled}
            placeholder="3"
            className={`h-8 w-full rounded-md border bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring ${errors.maxPausesPerTurn ? "border-destructive" : "border-input"}`}
            data-testid={`${idPrefix}-max-pauses`}
          />
          {errText(errors.maxPausesPerTurn)}
        </div>
        <div>
          <label className="mb-1 block text-[10px] text-muted-foreground">
            {t("agentDetail.toolMaxAuto", "Max auto-approvals / turn (0–10)")}
          </label>
          <input
            type="number"
            min={0}
            max={10}
            value={maxAutoDraft}
            onChange={(e) => setMaxAutoDraft(e.target.value)}
            onBlur={() => onChange({ maxAutoApprovalsPerTurn: numberOrNull(maxAutoDraft) })}
            disabled={disabled}
            placeholder="2"
            className={`h-8 w-full rounded-md border bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring ${errors.maxAutoApprovalsPerTurn ? "border-destructive" : "border-input"}`}
            data-testid={`${idPrefix}-max-auto`}
          />
          {errText(errors.maxAutoApprovalsPerTurn)}
        </div>
      </div>

      {/* onNoProgress */}
      <div>
        <label className="mb-1 block text-[10px] text-muted-foreground">
          {t("agentDetail.toolOnNoProgress", "On no-progress re-pause")}
        </label>
        <select
          value={value.onNoProgress ?? "WAIT_FOR_HUMAN"}
          onChange={(e) => onChange({ onNoProgress: e.target.value as HitlOnNoProgress })}
          disabled={disabled}
          className="w-full appearance-none rounded-lg border border-input bg-background px-3 py-1.5 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
          data-testid={`${idPrefix}-no-progress`}
        >
          <option value="WAIT_FOR_HUMAN">{noProgressPolicyLabel(t, "WAIT_FOR_HUMAN")}</option>
          <option value="AUTO_REJECT">{noProgressPolicyLabel(t, "AUTO_REJECT")}</option>
          <option value="ABORT">{noProgressPolicyLabel(t, "ABORT")}</option>
        </select>
      </div>

      {/* timeout policy override */}
      <div>
        <label className="mb-1 block text-[10px] text-muted-foreground">
          {t("agentDetail.toolTimeoutPolicy", "Tool-pause timeout policy")}
        </label>
        <select
          value={value.timeoutPolicy ?? ""}
          onChange={(e) => {
            const raw = e.target.value;
            const policy = (raw === "" ? null : raw) as HitlTimeoutPolicy | null;
            const updates: Partial<ToolApprovalsConfig> = { timeoutPolicy: policy };
            if (
              requiresApprovalTimeout(policy) &&
              !(value.approvalTimeout && isValidIsoDuration(value.approvalTimeout))
            ) {
              updates.approvalTimeout = "PT15M";
            }
            onChange(updates);
          }}
          disabled={disabled}
          className="w-full appearance-none rounded-lg border border-input bg-background px-3 py-1.5 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
          data-testid={`${idPrefix}-timeout-policy`}
        >
          <option value="">{t("agentDetail.toolTimeoutInherit", "Inherit from agent")}</option>
          <option value="WAIT_INDEFINITELY">{t("hitl.timeoutWaitIndefinitely", "Wait Indefinitely")}</option>
          <option value="AUTO_APPROVE">{t("hitl.timeoutAutoApprove", "Auto-Approve")}</option>
          <option value="AUTO_REJECT">{t("hitl.timeoutAutoReject", "Auto-Reject")}</option>
          <option value="ABORT">{t("hitl.timeoutAbort", "Abort")}</option>
        </select>
      </div>

      {finite && (
        <div>
          <label className="mb-1 block text-[10px] text-muted-foreground">
            {t("agentDetail.hitlApprovalTimeout", "Approval timeout (ISO-8601)")}
          </label>
          <input
            type="text"
            value={timeoutDraft}
            onChange={(e) => setTimeoutDraft(e.target.value)}
            onBlur={() => {
              const next = timeoutDraft.trim() || null;
              if (next && !isValidIsoDuration(next)) return; // don't persist an invalid duration
              if (next !== (value.approvalTimeout ?? null)) onChange({ approvalTimeout: next });
            }}
            disabled={disabled}
            placeholder="PT30M"
            className={`h-8 w-full rounded-md border bg-background px-2 text-xs font-mono text-foreground focus:outline-none focus:ring-1 focus:ring-ring ${errors.approvalTimeout ? "border-destructive" : "border-input"}`}
            data-testid={`${idPrefix}-approval-timeout`}
          />
          {errText(errors.approvalTimeout)}
        </div>
      )}

      {showDemotionWarn && (
        <p
          className="flex items-start gap-1.5 rounded-md border border-amber-500/30 bg-amber-500/5 px-2 py-1.5 text-[10px] text-amber-600"
          data-testid={`${idPrefix}-demotion-warning`}
        >
          <AlertTriangle className="mt-0.5 h-3 w-3 shrink-0" aria-hidden="true" />
          {t(
            "agentDetail.toolAutoApproveDemotion",
            "Agent-level AUTO_APPROVE does not apply to tool approvals — tool pauses will wait indefinitely unless you set a tool-pause timeout policy above.",
          )}
        </p>
      )}

      {/* pauseReason */}
      <div>
        <label className="mb-1 block text-[10px] text-muted-foreground">
          {t("agentDetail.toolPauseReason", "Approver reason ({toolNames} allowed)")}
        </label>
        <input
          type="text"
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          onBlur={() => onChange({ pauseReason: reason.trim() || null })}
          disabled={disabled}
          maxLength={MAX_PAUSE_REASON_LENGTH}
          placeholder={t("agentDetail.toolPauseReasonPlaceholder", "e.g. Approval required for {toolNames}")}
          className={`h-8 w-full rounded-md border bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring ${errors.pauseReason ? "border-destructive" : "border-input"}`}
          data-testid={`${idPrefix}-pause-reason`}
        />
        {errText(errors.pauseReason)}
      </div>

      {/* pendingMessage */}
      <div>
        <label className="mb-1 block text-[10px] text-muted-foreground">
          {t("agentDetail.toolPendingMessage", "End-user message while paused ({toolNames} allowed)")}
        </label>
        <input
          type="text"
          value={pending}
          onChange={(e) => setPending(e.target.value)}
          onBlur={() => onChange({ pendingMessage: pending.trim() || null })}
          disabled={disabled}
          maxLength={MAX_PAUSE_REASON_LENGTH}
          placeholder={t("agentDetail.toolPendingMessagePlaceholder", "e.g. Waiting for a human to approve {toolNames}…")}
          className={`h-8 w-full rounded-md border bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring ${errors.pendingMessage ? "border-destructive" : "border-input"}`}
          data-testid={`${idPrefix}-pending-message`}
        />
        {errText(errors.pendingMessage)}
      </div>

      <p className="text-[10px] text-muted-foreground">
        {t(
          "agentDetail.toolInGroupTurns",
          "Inside a group turn, a member's gated tool call is auto-rejected (REJECT).",
        )}
      </p>
    </div>
  );
}
