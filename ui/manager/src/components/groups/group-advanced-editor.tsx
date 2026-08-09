import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { Save, X, RefreshCw } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { useUpdateGroup } from "@/hooks/use-groups";
import { useAgentDescriptors, groupAgentsByName } from "@/hooks/use-agents";
import {
  ARTIFACT_DEFAULT_MAX_PER_DISCUSSION,
  CONTEXT_WINDOW_DEFAULT_MAX_RECENT_ENTRIES,
  FACILITATOR_DEFAULT_MAX_MOVES,
  RETRO_CEILING_MAX_PER_RUN,
  RETRO_CEILING_MAX_STORED,
  RETRO_DEFAULT_MAX_PER_RUN,
  RETRO_DEFAULT_MAX_STORED,
} from "@/lib/api/groups";
import type {
  AgentGroupConfiguration,
  AssignmentMode,
  FacilitatorCheckpoint,
  FacilitatorMove,
} from "@/lib/api/groups";

/**
 * Inline editor for the advanced collaboration block — transcript windowing
 * (I9), retro lessons (I8), shared artifacts (I17), the facilitator (I12) and
 * TASK_FORCE assignment mode (I18).
 *
 * These five shipped as a read-only summary that was hidden whenever nothing
 * was set, which is the state every existing group is in — so the features were
 * both unreachable and invisible from the Manager. This is the write path, built
 * to the same shape as {@link GroupHitlEditor}: one inline panel, a master
 * switch per feature, save through {@link useUpdateGroup}.
 *
 * Each feature is independently toggleable and each toggle writes `undefined`
 * when off rather than a disabled block, so turning something off leaves no
 * half-configured object behind for the backend to interpret.
 */

/** Moves that act mid-phase, so they only make sense at an EACH_REPEAT checkpoint. */
const MID_PHASE_MOVES: FacilitatorMove[] = ["END_PHASE", "EXTEND_PHASE"];

const ALL_MOVES: FacilitatorMove[] = [
  "CONTINUE",
  "END_PHASE",
  "EXTEND_PHASE",
  "CALL_VOTE",
  "RECRUIT",
  "ESCALATE_HUMAN",
];

const MOVE_FALLBACKS: Record<FacilitatorMove, string> = {
  CONTINUE: "Continue",
  END_PHASE: "End the phase early",
  EXTEND_PHASE: "Extend the phase",
  CALL_VOTE: "Call a vote",
  RECRUIT: "Recruit a member",
  ESCALATE_HUMAN: "Escalate to a human",
};

const CHECKPOINT_FALLBACKS: Record<FacilitatorCheckpoint, string> = {
  EACH_PHASE: "After each phase",
  EACH_REPEAT: "After each round within a phase",
};

/** Clamp a typed number into [1, ceiling], falling back when it is not a number at all. */
function boundedInt(raw: number, fallback: number, ceiling?: number): number {
  if (!Number.isFinite(raw)) return fallback;
  const floored = Math.floor(raw);
  if (floored < 1) return fallback;
  return ceiling != null ? Math.min(floored, ceiling) : floored;
}

export function GroupAdvancedEditor({
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
  const { data: agentDescriptors } = useAgentDescriptors(200);
  // Descriptors carry a resource URI, not an id, and one agent appears once per
  // version — `groupAgentsByName` is the shared helper that resolves both, same
  // as the create wizard's agent picker.
  const agents = agentDescriptors ? groupAgentsByName(agentDescriptors) : [];
  const uid = useId();

  const isTaskForce = config.style === "TASK_FORCE";

  // --- I9 transcript window ---
  const [windowEnabled, setWindowEnabled] = useState(!!config.contextWindow?.enabled);
  const [maxRecentEntries, setMaxRecentEntries] = useState(
    config.contextWindow?.maxRecentEntries ?? CONTEXT_WINDOW_DEFAULT_MAX_RECENT_ENTRIES,
  );
  // Only a literal `false` disables summarization; null/absent means "on".
  const [summarizeOverflow, setSummarizeOverflow] = useState(
    config.contextWindow?.summarizeOverflow !== false,
  );

  // --- I8 retro lessons ---
  const [retroEnabled, setRetroEnabled] = useState(!!config.retroConfig);
  const [maxLessonsPerRun, setMaxLessonsPerRun] = useState(
    config.retroConfig?.maxLessonsPerRun ?? RETRO_DEFAULT_MAX_PER_RUN,
  );
  const [maxStoredLessons, setMaxStoredLessons] = useState(
    config.retroConfig?.maxStoredLessons ?? RETRO_DEFAULT_MAX_STORED,
  );

  // --- I17 shared artifacts ---
  const [artifactsEnabled, setArtifactsEnabled] = useState(
    !!config.artifactConfig?.allowArtifactTools,
  );
  const [maxArtifacts, setMaxArtifacts] = useState(
    config.artifactConfig?.maxArtifactsPerDiscussion ?? ARTIFACT_DEFAULT_MAX_PER_DISCUSSION,
  );

  // --- I12 facilitator ---
  const [facilitatorEnabled, setFacilitatorEnabled] = useState(!!config.facilitator?.enabled);
  const [facilitatorAgentId, setFacilitatorAgentId] = useState(config.facilitator?.agentId ?? "");
  const [allowedMoves, setAllowedMoves] = useState<Set<FacilitatorMove>>(
    new Set(config.facilitator?.allowedMoves ?? ["CONTINUE"]),
  );
  const [checkAfter, setCheckAfter] = useState<FacilitatorCheckpoint>(
    config.facilitator?.checkAfter ?? "EACH_PHASE",
  );
  const [maxMoves, setMaxMoves] = useState(
    config.facilitator?.maxMovesPerDiscussion ?? FACILITATOR_DEFAULT_MAX_MOVES,
  );
  const [escalateTo, setEscalateTo] = useState(config.facilitator?.escalateTo ?? "");

  // --- I18 assignment mode ---
  const [assignmentMode, setAssignmentMode] = useState<AssignmentMode>(
    config.taskListConfig?.assignmentMode ?? "ROLE",
  );

  const toggleMove = (move: FacilitatorMove) =>
    setAllowedMoves((prev) => {
      const next = new Set(prev);
      if (next.has(move)) next.delete(move);
      else next.add(move);
      return next;
    });

  // A facilitator with no agent has nothing to ask, and the backend requires the
  // id when enabled — block rather than save a config that cannot run.
  const facilitatorAgentMissing = facilitatorEnabled && !facilitatorAgentId.trim();
  // ESCALATE_HUMAN pauses for a specific principal; without one the pause has
  // nobody to wait on.
  const escalateTargetMissing =
    facilitatorEnabled && allowedMoves.has("ESCALATE_HUMAN") && !escalateTo.trim();
  // END_PHASE/EXTEND_PHASE act mid-phase, so an EACH_PHASE checkpoint — which
  // only runs once the phase is already over — can never actually play them.
  const midPhaseMovesUnreachable =
    facilitatorEnabled &&
    checkAfter === "EACH_PHASE" &&
    MID_PHASE_MOVES.some((m) => allowedMoves.has(m));
  const noMoveSelected = facilitatorEnabled && allowedMoves.size === 0;

  const blocked =
    facilitatorAgentMissing || escalateTargetMissing || midPhaseMovesUnreachable || noMoveSelected;

  const save = () => {
    if (blocked) return;

    const next: AgentGroupConfiguration = {
      ...config,
      contextWindow: windowEnabled
        ? {
            ...config.contextWindow,
            enabled: true,
            maxRecentEntries: boundedInt(
              maxRecentEntries,
              CONTEXT_WINDOW_DEFAULT_MAX_RECENT_ENTRIES,
            ),
            summarizeOverflow,
          }
        : undefined,
      retroConfig: retroEnabled
        ? {
            maxLessonsPerRun: boundedInt(
              maxLessonsPerRun,
              RETRO_DEFAULT_MAX_PER_RUN,
              RETRO_CEILING_MAX_PER_RUN,
            ),
            maxStoredLessons: boundedInt(
              maxStoredLessons,
              RETRO_DEFAULT_MAX_STORED,
              RETRO_CEILING_MAX_STORED,
            ),
          }
        : undefined,
      artifactConfig: artifactsEnabled
        ? {
            ...config.artifactConfig,
            allowArtifactTools: true,
            maxArtifactsPerDiscussion: boundedInt(
              maxArtifacts,
              ARTIFACT_DEFAULT_MAX_PER_DISCUSSION,
            ),
            // Validators are a declarative allow-list this editor does not yet
            // surface; preserve whatever is already saved rather than dropping it.
            validators: config.artifactConfig?.validators ?? [],
          }
        : undefined,
      facilitator: facilitatorEnabled
        ? {
            enabled: true,
            agentId: facilitatorAgentId.trim(),
            allowedMoves: [...allowedMoves],
            checkAfter,
            maxMovesPerDiscussion: boundedInt(maxMoves, FACILITATOR_DEFAULT_MAX_MOVES),
            // Only meaningful when ESCALATE_HUMAN is allowed; drop an orphaned
            // draft so we do not persist a target nothing can reach.
            escalateTo: allowedMoves.has("ESCALATE_HUMAN") ? escalateTo.trim() : null,
          }
        : undefined,
      // Assignment mode lives on taskListConfig and only applies to TASK_FORCE.
      // Leave the block untouched for every other style rather than conjuring one.
      taskListConfig:
        isTaskForce && config.taskListConfig
          ? { ...config.taskListConfig, assignmentMode }
          : config.taskListConfig,
    };

    update.mutate(
      { id: groupId, version: groupVersion, config: next },
      {
        onSuccess: () => {
          toast.success(t("groups.advancedSaved", "Collaboration settings saved"));
          onDone();
        },
        onError: () => toast.error(t("common.error", "Something went wrong")),
      },
    );
  };

  const inputCls =
    "h-8 w-full rounded-md border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring";
  const numCls = `${inputCls} w-20`;

  return (
    <div
      className="rounded-lg border border-border bg-secondary/30 p-2.5 space-y-3"
      data-testid="group-advanced-editor"
    >
      {/* ---- I9 transcript window ---- */}
      <section className="space-y-1.5">
        <label className="flex items-center gap-2 text-xs font-medium text-foreground">
          <input
            type="checkbox"
            checked={windowEnabled}
            onChange={(e) => setWindowEnabled(e.target.checked)}
            className="h-3.5 w-3.5 rounded border-input accent-primary"
            data-testid="adv-window-enable"
          />
          {t("groups.contextWindowLabel", "Transcript window")}
        </label>
        <p className="text-[10px] text-muted-foreground">
          {t(
            "groups.contextWindowHint",
            "Keeps each turn's context bounded on long discussions by folding older entries into a rolling summary.",
          )}
        </p>
        {windowEnabled && (
          <div className="space-y-1.5 ps-5">
            <div className="flex items-center gap-2">
              <label htmlFor={`${uid}-recent`} className="text-[10px] text-muted-foreground">
                {t("groups.contextWindowEntries", "Recent entries kept in full")}
              </label>
              <input
                id={`${uid}-recent`}
                type="number"
                min={1}
                value={maxRecentEntries}
                onChange={(e) => setMaxRecentEntries(Number(e.target.value))}
                className={numCls}
                data-testid="adv-window-entries"
              />
            </div>
            <label className="flex items-center gap-2 text-[11px] text-foreground">
              <input
                type="checkbox"
                checked={summarizeOverflow}
                onChange={(e) => setSummarizeOverflow(e.target.checked)}
                className="h-3 w-3 rounded border-input accent-primary"
                data-testid="adv-window-summarize"
              />
              {t("groups.contextWindowSummarize", "Summarize what falls out of the window")}
            </label>
            {!summarizeOverflow && (
              <p className="text-[10px] text-muted-foreground" data-testid="adv-window-truncate-note">
                {t(
                  "groups.contextWindowTruncateNote",
                  "Older entries are replaced with a plain \"N earlier entries omitted\" marker instead.",
                )}
              </p>
            )}
          </div>
        )}
      </section>

      {/* ---- I8 retro lessons ---- */}
      <section className="space-y-1.5 border-t border-border pt-2.5">
        <label className="flex items-center gap-2 text-xs font-medium text-foreground">
          <input
            type="checkbox"
            checked={retroEnabled}
            onChange={(e) => setRetroEnabled(e.target.checked)}
            className="h-3.5 w-3.5 rounded border-input accent-primary"
            data-testid="adv-retro-enable"
          />
          {t("groups.retroConfigLabel", "Retro lessons")}
        </label>
        <p className="text-[10px] text-muted-foreground">
          {t(
            "groups.retroConfigHint",
            "Lets a retro phase write lessons into the team's memory, carried into later discussions.",
          )}
        </p>
        {retroEnabled && (
          <div className="flex flex-wrap items-center gap-3 ps-5">
            <div className="flex items-center gap-2">
              <label htmlFor={`${uid}-per-run`} className="text-[10px] text-muted-foreground">
                {t("groups.retroPerRun", "Per run")}
              </label>
              <input
                id={`${uid}-per-run`}
                type="number"
                min={1}
                max={RETRO_CEILING_MAX_PER_RUN}
                value={maxLessonsPerRun}
                onChange={(e) => setMaxLessonsPerRun(Number(e.target.value))}
                className={numCls}
                data-testid="adv-retro-per-run"
              />
            </div>
            <div className="flex items-center gap-2">
              <label htmlFor={`${uid}-stored`} className="text-[10px] text-muted-foreground">
                {t("groups.retroStored", "Stored max")}
              </label>
              <input
                id={`${uid}-stored`}
                type="number"
                min={1}
                max={RETRO_CEILING_MAX_STORED}
                value={maxStoredLessons}
                onChange={(e) => setMaxStoredLessons(Number(e.target.value))}
                className={numCls}
                data-testid="adv-retro-stored"
              />
            </div>
          </div>
        )}
      </section>

      {/* ---- I17 shared artifacts ---- */}
      <section className="space-y-1.5 border-t border-border pt-2.5">
        <label className="flex items-center gap-2 text-xs font-medium text-foreground">
          <input
            type="checkbox"
            checked={artifactsEnabled}
            onChange={(e) => setArtifactsEnabled(e.target.checked)}
            className="h-3.5 w-3.5 rounded border-input accent-primary"
            data-testid="adv-artifacts-enable"
          />
          {t("groups.artifactConfigLabel", "Shared artifacts")}
        </label>
        <p className="text-[10px] text-muted-foreground">
          {t(
            "groups.artifactConfigHint",
            "Lets members co-author documents that survive the turn they were written in.",
          )}
        </p>
        {artifactsEnabled && (
          <div className="flex items-center gap-2 ps-5">
            <label htmlFor={`${uid}-artifacts`} className="text-[10px] text-muted-foreground">
              {t("groups.artifactsPerDiscussion", "Max per discussion")}
            </label>
            <input
              id={`${uid}-artifacts`}
              type="number"
              min={1}
              value={maxArtifacts}
              onChange={(e) => setMaxArtifacts(Number(e.target.value))}
              className={numCls}
              data-testid="adv-artifacts-max"
            />
          </div>
        )}
      </section>

      {/* ---- I12 facilitator ---- */}
      <section className="space-y-1.5 border-t border-border pt-2.5">
        <label className="flex items-center gap-2 text-xs font-medium text-foreground">
          <input
            type="checkbox"
            checked={facilitatorEnabled}
            onChange={(e) => setFacilitatorEnabled(e.target.checked)}
            className="h-3.5 w-3.5 rounded border-input accent-primary"
            data-testid="adv-facilitator-enable"
          />
          {t("groups.facilitatorLabel", "Facilitator")}
        </label>
        <p className="text-[10px] text-muted-foreground">
          {t(
            "groups.facilitatorHint",
            "An agent that checks in at each checkpoint and picks one move from the list you allow — never a free-form orchestrator.",
          )}
        </p>
        {facilitatorEnabled && (
          <div className="space-y-2 ps-5">
            <div className="space-y-1">
              <label htmlFor={`${uid}-fac-agent`} className="block text-[10px] text-muted-foreground">
                {t("groups.facilitatorAgent", "Facilitator agent")}
              </label>
              <select
                id={`${uid}-fac-agent`}
                value={facilitatorAgentId}
                onChange={(e) => setFacilitatorAgentId(e.target.value)}
                className={inputCls}
                data-testid="adv-facilitator-agent"
              >
                <option value="">{t("groups.facilitatorPickAgent", "Select an agent…")}</option>
                {agents.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.name || a.id}
                  </option>
                ))}
              </select>
              {facilitatorAgentMissing && (
                <p className="text-[10px] text-destructive" data-testid="adv-facilitator-agent-error">
                  {t("groups.facilitatorAgentRequired", "Pick the agent that will facilitate.")}
                </p>
              )}
            </div>

            <div className="space-y-1">
              <label htmlFor={`${uid}-fac-check`} className="block text-[10px] text-muted-foreground">
                {t("groups.facilitatorCheckAfter", "Check in")}
              </label>
              <select
                id={`${uid}-fac-check`}
                value={checkAfter}
                onChange={(e) => setCheckAfter(e.target.value as FacilitatorCheckpoint)}
                className={inputCls}
                data-testid="adv-facilitator-checkpoint"
              >
                {(Object.keys(CHECKPOINT_FALLBACKS) as FacilitatorCheckpoint[]).map((c) => (
                  <option key={c} value={c}>
                    {t(`groups.facilitatorCheckpoint.${c}`, CHECKPOINT_FALLBACKS[c])}
                  </option>
                ))}
              </select>
            </div>

            <div className="space-y-1">
              <p className="text-[10px] text-muted-foreground">
                {t("groups.facilitatorMoves", "Allowed moves")}
              </p>
              <div className="space-y-1">
                {ALL_MOVES.map((m) => (
                  <label key={m} className="flex items-center gap-2 text-[11px] text-foreground">
                    <input
                      type="checkbox"
                      checked={allowedMoves.has(m)}
                      onChange={() => toggleMove(m)}
                      className="h-3 w-3 rounded border-input accent-primary"
                      data-testid={`adv-facilitator-move-${m}`}
                    />
                    {t(`groups.facilitatorMove.${m}`, MOVE_FALLBACKS[m])}
                  </label>
                ))}
              </div>
              {noMoveSelected && (
                <p className="text-[10px] text-destructive" data-testid="adv-facilitator-no-move">
                  {t("groups.facilitatorNoMove", "Allow at least one move, or turn the facilitator off.")}
                </p>
              )}
              {midPhaseMovesUnreachable && (
                <p className="text-[10px] text-destructive" data-testid="adv-facilitator-midphase-error">
                  {t(
                    "groups.facilitatorMidPhaseNeedsRepeat",
                    "Ending or extending a phase acts mid-phase, so it needs the per-round checkpoint.",
                  )}
                </p>
              )}
            </div>

            {allowedMoves.has("ESCALATE_HUMAN") && (
              <div className="space-y-1">
                <label htmlFor={`${uid}-escalate`} className="block text-[10px] text-muted-foreground">
                  {t("groups.facilitatorEscalateTo", "Escalate to (principal id)")}
                </label>
                <input
                  id={`${uid}-escalate`}
                  value={escalateTo}
                  onChange={(e) => setEscalateTo(e.target.value)}
                  className={inputCls}
                  data-testid="adv-facilitator-escalate"
                />
                {escalateTargetMissing && (
                  <p className="text-[10px] text-destructive" data-testid="adv-facilitator-escalate-error">
                    {t(
                      "groups.facilitatorEscalateRequired",
                      "Escalation pauses the discussion for someone — name who.",
                    )}
                  </p>
                )}
              </div>
            )}

            <div className="flex items-center gap-2">
              <label htmlFor={`${uid}-max-moves`} className="text-[10px] text-muted-foreground">
                {t("groups.facilitatorMaxMoves", "Max moves per discussion")}
              </label>
              <input
                id={`${uid}-max-moves`}
                type="number"
                min={1}
                value={maxMoves}
                onChange={(e) => setMaxMoves(Number(e.target.value))}
                className={numCls}
                data-testid="adv-facilitator-max-moves"
              />
            </div>
          </div>
        )}
      </section>

      {/* ---- I18 assignment mode (TASK_FORCE only) ---- */}
      {isTaskForce && config.taskListConfig && (
        <section className="space-y-1.5 border-t border-border pt-2.5">
          <label htmlFor={`${uid}-assignment`} className="block text-xs font-medium text-foreground">
            {t("groups.assignmentModeLabel", "Task assignment")}
          </label>
          <select
            id={`${uid}-assignment`}
            value={assignmentMode}
            onChange={(e) => setAssignmentMode(e.target.value as AssignmentMode)}
            className={inputCls}
            data-testid="adv-assignment-mode"
          >
            <option value="ROLE">{t("groups.assignmentModeRole", "By role (planner assigns)")}</option>
            <option value="BID">{t("groups.assignmentModeBid", "By bid (members bid, highest confidence wins)")}</option>
          </select>
          <p className="text-[10px] text-muted-foreground">
            {assignmentMode === "BID"
              ? t(
                  "groups.assignmentModeBidHint",
                  "Falls back to role assignment when an auction cannot pay for itself — fewer than two bidders or two open tasks, or too little turn budget.",
                )
              : t("groups.assignmentModeRoleHint", "The planner or config assigns each task, or round-robin.")}
          </p>
        </section>
      )}

      {/* ---- actions ---- */}
      <div className="flex justify-end gap-2 border-t border-border pt-2.5">
        <Button variant="ghost" size="sm" onClick={onDone} className="h-7 text-xs">
          <X className="me-1 h-3 w-3" />
          {t("common.cancel", "Cancel")}
        </Button>
        <Button
          size="sm"
          onClick={save}
          disabled={blocked || update.isPending}
          className="h-7 text-xs"
          data-testid="adv-save"
        >
          {update.isPending ? (
            <RefreshCw className="me-1 h-3 w-3 animate-spin" />
          ) : (
            <Save className="me-1 h-3 w-3" />
          )}
          {t("common.save", "Save")}
        </Button>
      </div>
    </div>
  );
}
