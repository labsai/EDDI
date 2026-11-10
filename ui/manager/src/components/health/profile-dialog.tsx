import { useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import type { HealthProfile, MeasureUnit, ProfileViolation } from "@/lib/api/health";
import { normalizeProfile, profileViolations } from "@/lib/api/health";
import { useHealthPresets, useHealthProfile, useResetHealthProfile, useSaveHealthProfile } from "@/hooks/use-agent-health";
import { AccessibleDialog, Button, Skeleton, getErrorMessage } from "./deps";
import { Field, OverrideRows, ThresholdRows, Violations } from "./profile-fields";
import { fromInput, inputClass, toInput } from "./profile-utils";

export interface ProfileMeasureInfo {
  units: Record<string, MeasureUnit>;
  labels: Record<string, string>;
}

function ProfileForm({
  agentId,
  initial,
  info,
  onDone,
}: {
  agentId: string;
  initial: HealthProfile;
  info: ProfileMeasureInfo;
  onDone: () => void;
}) {
  const { t } = useTranslation();
  const presets = useHealthPresets();
  const save = useSaveHealthProfile(agentId);
  const reset = useResetHealthProfile(agentId);
  const [draft, setDraft] = useState<HealthProfile>(() => normalizeProfile(initial));
  const [violations, setViolations] = useState<ProfileViolation[]>([]);
  const [confirmReset, setConfirmReset] = useState(false);
  const measureIds = Object.keys(info.units);

  const submit = () => {
    setViolations([]);
    save.mutate(draft, {
      onSuccess: () => {
        toast.success(t("health.profile.saved", "Health profile saved"));
        onDone();
      },
      onError: (error) => setViolations(profileViolations(error)),
    });
  };

  const toggleCritical = (id: string) =>
    setDraft({ ...draft, critical: draft.critical.includes(id) ? draft.critical.filter((c) => c !== id) : [...draft.critical, id] });
  const setSlo = (key: "agentTurnSuccess" | "apdexTMs" | "minTurnsForBurnAlert", text: string) =>
    setDraft({ ...draft, slo: { ...draft.slo, [key]: fromInput(text, key === "agentTurnSuccess" ? "ratio" : "count") } });
  const unmatched = violations.filter(
    (v) => !/^(preset|critical|thresholds|budgets|slo|overrides)/.test(v.path),
  );
  const addable = measureIds.filter((id) => !(id in draft.thresholds));

  return (
    <form
      className="space-y-4"
      onSubmit={(e) => {
        e.preventDefault();
        submit();
      }}
      data-testid="health-profile-form"
    >
      {!initial.stored && (
        <p className="text-xs text-muted-foreground">
          {t("health.profile.defaults", "No profile saved yet: these are the defaults. Saving stores them for this agent.")}
        </p>
      )}
      {save.isError && violations.length === 0 && (
        <p className="text-sm text-destructive" role="alert">{getErrorMessage(save.error)}</p>
      )}
      {unmatched.length > 0 && (
        <ul className="text-sm text-destructive" role="alert">
          {unmatched.map((v) => (
            <li key={v.path}>{`${v.path}: ${v.message}`}</li>
          ))}
        </ul>
      )}

      <Field label={t("health.profile.preset", "Preset")} violations={violations} path="preset">
        <select className={inputClass} value={draft.preset} onChange={(e) => setDraft({ ...draft, preset: e.target.value })} data-testid="health-preset">
          {[...new Set([draft.preset, ...(presets.data ?? []).map((p) => p.id)])].map((id) => (
            <option key={id} value={id}>
              {presets.data?.find((p) => p.id === id)?.label ?? id}
            </option>
          ))}
        </select>
      </Field>

      <fieldset>
        <legend className="mb-1 text-xs font-medium">{t("health.profile.critical", "Critical measures")}</legend>
        <p className="mb-1 text-xs text-muted-foreground">{t("health.profile.criticalHint", "A critical measure in the red makes the whole agent need attention.")}</p>
        <div className="flex flex-wrap gap-x-4 gap-y-1">
          {measureIds.map((id) => (
            <label key={id} className="flex items-center gap-1.5 text-xs">
              <input type="checkbox" checked={draft.critical.includes(id)} onChange={() => toggleCritical(id)} />
              {info.labels[id] ?? id}
            </label>
          ))}
        </div>
        <Violations violations={violations} path="critical" />
      </fieldset>

      <fieldset className="space-y-2">
        <legend className="mb-1 text-xs font-medium">{t("health.profile.thresholds", "Thresholds")}</legend>
        <ThresholdRows profile={draft} onChange={setDraft} units={info.units} labels={info.labels} violations={violations} />
        {addable.length > 0 && (
          <select
            className={inputClass}
            aria-label={t("health.profile.addThreshold", "Add a threshold")}
            value=""
            onChange={(e) => e.target.value && setDraft({ ...draft, thresholds: { ...draft.thresholds, [e.target.value]: {} } })}
          >
            <option value="">{t("health.profile.addThreshold", "Add a threshold")}</option>
            {addable.map((id) => (
              <option key={id} value={id}>{info.labels[id] ?? id}</option>
            ))}
          </select>
        )}
      </fieldset>

      <fieldset className="grid gap-3 sm:grid-cols-2">
        <legend className="mb-1 text-xs font-medium">{t("health.profile.budgetsAndSlo", "Budgets and service level")}</legend>
        <Field label={t("health.profile.costPerResolved", "Cost per resolved session (USD)")} violations={violations} path="budgets.costPerResolvedUsd">
          <input
            className={inputClass}
            inputMode="decimal"
            value={toInput(draft.budgets.costPerResolvedUsd, "usd")}
            onChange={(e) => {
              const v = fromInput(e.target.value, "usd");
              const budgets = { ...draft.budgets };
              if (v === undefined) delete budgets.costPerResolvedUsd;
              else budgets.costPerResolvedUsd = v;
              setDraft({ ...draft, budgets });
            }}
          />
        </Field>
        <Field label={t("health.profile.sloSuccess", "Turn success target (%)")} violations={violations} path="slo.agentTurnSuccess">
          <input className={inputClass} inputMode="decimal" value={toInput(draft.slo.agentTurnSuccess, "ratio")} onChange={(e) => setSlo("agentTurnSuccess", e.target.value)} />
        </Field>
        <Field label={t("health.profile.sloApdex", "Apdex target time (ms)")} violations={violations} path="slo.apdexTMs">
          <input className={inputClass} inputMode="numeric" value={toInput(draft.slo.apdexTMs, "ms")} onChange={(e) => setSlo("apdexTMs", e.target.value)} />
        </Field>
        <Field label={t("health.profile.sloMinTurns", "Minimum turns before a burn alert")} violations={violations} path="slo.minTurnsForBurnAlert">
          <input className={inputClass} inputMode="numeric" value={toInput(draft.slo.minTurnsForBurnAlert, "count")} onChange={(e) => setSlo("minTurnsForBurnAlert", e.target.value)} />
        </Field>
      </fieldset>

      <fieldset>
        <legend className="mb-1 text-xs font-medium">{t("health.profile.overrides", "Temporary overrides")}</legend>
        <p className="mb-1 text-xs text-muted-foreground">
          {t("health.profile.overridesHint", "Ignore a measure for a while, with a reason. An override that has expired is dropped silently.")}
        </p>
        <OverrideRows overrides={draft.overrides} onChange={(overrides) => setDraft({ ...draft, overrides })} measureIds={measureIds} violations={violations} />
      </fieldset>

      <div className="flex flex-wrap justify-between gap-2 border-t border-border pt-3">
        {initial.stored ? (
          <Button
            type="button"
            variant={confirmReset ? "destructive" : "outline"}
            size="sm"
            disabled={reset.isPending}
            onClick={() => (confirmReset ? reset.mutate(undefined, { onSuccess: onDone }) : setConfirmReset(true))}
            data-testid="health-profile-reset"
          >
            {confirmReset ? t("health.profile.confirmReset", "Confirm: back to defaults") : t("health.profile.reset", "Reset to defaults")}
          </Button>
        ) : (
          <span />
        )}
        <div className="flex gap-2">
          <Button type="button" variant="ghost" size="sm" onClick={onDone}>{t("common.cancel", "Cancel")}</Button>
          <Button type="submit" size="sm" disabled={save.isPending} data-testid="health-profile-save">{t("common.save", "Save")}</Button>
        </div>
      </div>
    </form>
  );
}

/** Edit the agent's health profile (EDIT only: the panel does not offer it otherwise). */
export function ProfileDialog({
  open,
  onClose,
  agentId,
  info,
}: {
  open: boolean;
  onClose: () => void;
  agentId: string;
  info: ProfileMeasureInfo;
}) {
  const { t } = useTranslation();
  const profile = useHealthProfile(agentId, open);
  return (
    <AccessibleDialog open={open} onClose={onClose} title={t("health.profile.title", "Health profile")} maxWidth="max-w-2xl" testId="health-profile-dialog">
      {profile.isLoading && <Skeleton className="h-40 w-full" />}
      {profile.isError && <p className="text-sm text-destructive" role="alert">{getErrorMessage(profile.error)}</p>}
      {profile.data && <ProfileForm agentId={agentId} initial={profile.data} info={info} onDone={onClose} />}
    </AccessibleDialog>
  );
}
