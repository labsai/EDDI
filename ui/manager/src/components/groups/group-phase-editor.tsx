import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Save, X, RefreshCw, Info } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { useUpdateGroup } from "@/hooks/use-groups";
import type {
  AgentGroupConfiguration,
  ConvergenceConfig,
  ConvergenceJudge,
  DiscussionPhase,
} from "@/lib/api/groups";
import { getStylePhases } from "@/lib/hitl-config";
import {
  CONVERGENCE_MIN_REPEATS_FLOOR,
  DEFAULT_CONVERGENCE_THRESHOLD,
  convergenceApplies,
  normalizeConvergence,
} from "@/lib/group-config";

/**
 * Per-phase behaviour that EDDI's Wave 1 added and no Manager surface could
 * reach: abstention (I4) and convergence-based early exit (I2).
 *
 * Both live on `DiscussionPhase`, and a preset-style group stores `phases: null`
 * because the engine expands the preset at runtime — so, exactly as the HITL
 * editor does for `requiresApproval`, enabling either one materializes the phase
 * list into the saved config using the same behaviour-preserving expansion.
 * Every other field on a stored phase (including `requiresApproval`) is carried
 * through untouched, so this editor and the approval editor compose.
 */
export function GroupPhaseEditor({
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
  const [phases, setPhases] = useState<DiscussionPhase[]>(() =>
    basePhases.map((p) => ({ ...p })),
  );

  const patchPhase = (index: number, patch: Partial<DiscussionPhase>) =>
    setPhases((prev) => prev.map((p, i) => (i === index ? { ...p, ...patch } : p)));

  const patchConvergence = (index: number, patch: Partial<ConvergenceConfig>) =>
    setPhases((prev) =>
      prev.map((p, i) =>
        i === index
          ? {
              ...p,
              convergence: normalizeConvergence({
                ...(p.convergence ?? { enabled: false }),
                ...patch,
              }),
            }
          : p,
      ),
    );

  const save = () => {
    // Drop a convergence block that is off rather than persisting a disabled
    // object: `null` is what the backend means by "no convergence detection", and
    // storing an explicit off-block makes every phase in the document look
    // configured for something it is not.
    const cleaned: DiscussionPhase[] = phases.map((p) => ({
      ...p,
      convergence: p.convergence?.enabled ? normalizeConvergence(p.convergence) : null,
    }));
    const next: AgentGroupConfiguration = { ...config, phases: cleaned };
    update.mutate(
      { id: groupId, version: groupVersion, config: next },
      {
        onSuccess: () => {
          toast.success(t("groups.phasesSaved", "Phase settings saved"));
          onDone();
        },
        onError: () => toast.error(t("common.error", "Something went wrong")),
      },
    );
  };

  const inputCls =
    "h-8 w-full rounded-md border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring";

  if (phases.length === 0) {
    return (
      <div className="rounded-lg border border-border bg-secondary/20 p-2.5" data-testid="group-phase-editor">
        <p className="text-[10px] text-muted-foreground">
          {t(
            "groups.phasesNoneToEdit",
            "This group defines no phases. Choose a discussion style, or add phases to the config, before tuning per-phase behaviour.",
          )}
        </p>
        <Button variant="outline" size="sm" className="mt-2 h-7 text-xs" onClick={onDone}>
          {t("common.close", "Close")}
        </Button>
      </div>
    );
  }

  return (
    <div
      className="space-y-2.5 rounded-lg border border-violet-500/30 bg-violet-500/5 p-2.5"
      data-testid="group-phase-editor"
    >
      {/* Explained ONCE, here. These two sentences used to sit under every phase
          — three copies for a PEER_REVIEW group, six for a TASK_FORCE one — which
          in a sidebar this narrow buried the controls they were describing. */}
      <dl className="space-y-1 border-b border-violet-500/20 pb-2 text-[10px] text-muted-foreground">
        <div>
          <dt className="inline font-medium text-foreground">
            {t("groups.allowAbstention", "Allow abstention")}
            {" — "}
          </dt>
          <dd className="inline">
            {t(
              "groups.allowAbstentionHelp",
              "A member may decline to add anything new this round instead of restating itself.",
            )}
          </dd>
        </div>
        <div>
          <dt className="inline font-medium text-foreground">
            {t("groups.convergenceEnable", "Stop early on convergence")}
            {" — "}
          </dt>
          <dd className="inline">
            {t(
              "groups.convergenceHelp",
              "A judge compares each repeat with the previous one and ends the phase once the members stop moving. Costs one call per repeat.",
            )}{" "}
            {t(
              "groups.convergenceNeedsRepeats",
              "Only available on a phase that repeats — a single pass has nothing to compare against.",
            )}
          </dd>
        </div>
      </dl>

      {phases.map((phase, idx) => {
        const repeats = phase.repeats ?? 1;
        const canConverge = convergenceApplies(phase);
        const convergence = phase.convergence;
        // Comparing more repeats than the phase has would never converge.
        const maxMinRepeats = Math.max(repeats, CONVERGENCE_MIN_REPEATS_FLOOR);
        // Convergence is switched on for a phase that runs once — legal to store
        // via the API, impossible to act on. Distinct from simply unavailable.
        const convergenceInert = !canConverge && !!convergence?.enabled;
        const convergenceUnavailable = !canConverge && !convergence?.enabled;
        return (
          <div key={`${phase.name}-${idx}`} className="rounded-md border border-border bg-background/60 p-2">
            <div className="mb-1.5 flex items-center gap-1.5">
              <span className="text-xs font-semibold text-foreground">{phase.name}</span>
              {repeats > 1 && (
                <span className="rounded bg-muted px-1 text-[9px] text-muted-foreground">
                  {t("groups.phaseRepeats", "×{{repeats}}", { repeats })}
                </span>
              )}
            </div>

            {/* Abstention (I4) */}
            <label className="flex items-start gap-2 text-[11px] text-foreground">
              <input
                type="checkbox"
                checked={!!phase.allowAbstention}
                onChange={(e) => patchPhase(idx, { allowAbstention: e.target.checked })}
                className="mt-0.5 h-3 w-3 rounded border-input accent-primary"
                data-testid={`phase-abstention-${idx}`}
              />
              <span>{t("groups.allowAbstention", "Allow abstention")}</span>
            </label>

            {/* Convergence (I2) */}
            <label className="mt-2 flex items-start gap-2 text-[11px] text-foreground">
              <input
                type="checkbox"
                checked={!!convergence?.enabled}
                // Disabled only when there is nothing to turn OFF. A config
                // authored through the API can carry convergence on a phase that
                // runs once; a flat `disabled={!canConverge}` would render that
                // checkbox checked and frozen, leaving an inert setting the UI
                // could show but never clear.
                disabled={convergenceUnavailable}
                title={
                  convergenceUnavailable
                    ? t(
                        "groups.convergenceNeedsRepeats",
                        "Only available on a phase that repeats — a single pass has nothing to compare against.",
                      )
                    : undefined
                }
                onChange={(e) =>
                  patchConvergence(idx, {
                    enabled: e.target.checked,
                    // Seed the backend defaults on first enable so the saved
                    // document says what will actually run.
                    minRepeats: convergence?.minRepeats ?? CONVERGENCE_MIN_REPEATS_FLOOR,
                    threshold: convergence?.threshold ?? DEFAULT_CONVERGENCE_THRESHOLD,
                    judge: convergence?.judge ?? "MODERATOR",
                  })
                }
                className="mt-0.5 h-3 w-3 rounded border-input accent-primary disabled:opacity-40"
                data-testid={`phase-convergence-enable-${idx}`}
              />
              <span className={convergenceUnavailable ? "opacity-60" : undefined}>
                {t("groups.convergenceEnable", "Stop early on convergence")}
                {/* The only per-phase note left: convergence switched on for a
                    phase that runs once is specific to THIS phase and needs
                    attention, unlike the general explanation in the legend. */}
                {convergenceInert && (
                  <span
                    className="block text-[10px] text-amber-600 dark:text-amber-400"
                    data-testid={`phase-convergence-inert-${idx}`}
                  >
                    {t(
                      "groups.convergenceInert",
                      "Configured, but this phase runs once — the judge can never run. Clear it, or give the phase more than one repeat.",
                    )}
                  </span>
                )}
              </span>
            </label>

            {canConverge && convergence?.enabled && (
              <div className="mt-2 grid grid-cols-3 gap-2">
                <div>
                  <label
                    htmlFor={`phase-convergence-min-${idx}`}
                    className="mb-0.5 block text-[10px] text-muted-foreground"
                  >
                    {t("groups.convergenceMinRepeats", "Min repeats")}
                  </label>
                  <input
                    id={`phase-convergence-min-${idx}`}
                    type="number"
                    min={CONVERGENCE_MIN_REPEATS_FLOOR}
                    max={maxMinRepeats}
                    step={1}
                    value={convergence.minRepeats}
                    onChange={(e) => {
                      // `min`/`max`/`step` bound the spinner, not the value —
                      // a paste survives all three. `normalizeConvergence`
                      // applies only the floor, so a minRepeats above the
                      // phase's own repeat count would save a config whose
                      // judge can never run, silently disabling the feature
                      // that was just switched on.
                      const v = e.currentTarget.valueAsNumber;
                      if (!Number.isFinite(v)) return;
                      patchConvergence(idx, {
                        minRepeats: Math.min(
                          maxMinRepeats,
                          Math.max(CONVERGENCE_MIN_REPEATS_FLOOR, Math.trunc(v)),
                        ),
                      });
                    }}
                    className={inputCls}
                    data-testid={`phase-convergence-min-${idx}`}
                  />
                </div>
                <div>
                  <label
                    htmlFor={`phase-convergence-threshold-${idx}`}
                    className="mb-0.5 block text-[10px] text-muted-foreground"
                  >
                    {t("groups.convergenceThreshold", "Threshold")}
                  </label>
                  <input
                    id={`phase-convergence-threshold-${idx}`}
                    type="number"
                    min={0.1}
                    max={1}
                    step={0.05}
                    value={convergence.threshold}
                    onChange={(e) => {
                      // Out-of-range values are left to normalizeConvergence,
                      // which mirrors the backend and falls back to the default
                      // for anything outside (0,1] — clamping here instead would
                      // silently turn a typo into a plausible-looking threshold.
                      const v = e.currentTarget.valueAsNumber;
                      if (Number.isFinite(v)) patchConvergence(idx, { threshold: v });
                    }}
                    className={inputCls}
                    data-testid={`phase-convergence-threshold-${idx}`}
                  />
                </div>
                <div>
                  <label
                    htmlFor={`phase-convergence-judge-${idx}`}
                    className="mb-0.5 block text-[10px] text-muted-foreground"
                  >
                    {t("groups.convergenceJudge", "Judge")}
                  </label>
                  <select
                    id={`phase-convergence-judge-${idx}`}
                    value={convergence.judge}
                    onChange={(e) => patchConvergence(idx, { judge: e.target.value as ConvergenceJudge })}
                    className={inputCls}
                    data-testid={`phase-convergence-judge-${idx}`}
                  >
                    <option value="MODERATOR">{t("groups.judgeModerator", "Moderator")}</option>
                    <option value="SERVICE">{t("groups.judgeService", "Service")}</option>
                  </select>
                </div>
              </div>
            )}

            {canConverge && convergence?.enabled && convergence.judge === "SERVICE" && (
              <p
                className="mt-1.5 flex items-start gap-1 text-[10px] text-muted-foreground"
                data-testid={`phase-convergence-service-note-${idx}`}
              >
                <Info className="mt-0.5 h-2.5 w-2.5 shrink-0" aria-hidden="true" />
                {t(
                  "groups.judgeServiceNote",
                  "Accepted but not yet wired — the engine falls back to the moderator and logs a warning.",
                )}
              </p>
            )}
          </div>
        );
      })}

      <div className="flex gap-2 pt-1">
        <Button
          size="sm"
          className="h-7 flex-1 text-xs"
          onClick={save}
          disabled={update.isPending}
          data-testid="group-phase-save"
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
          data-testid="group-phase-cancel"
        >
          <X className="h-3 w-3 me-1" />
          {t("common.cancel", "Cancel")}
        </Button>
      </div>
    </div>
  );
}
