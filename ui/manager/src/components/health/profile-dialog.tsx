import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import type { HealthProfile, MeasureUnit, ProfileViolation } from "./api";
import { normalizeProfile, profileViolations } from "./api";
import { useHealthPresets, useHealthProfile, useResetHealthProfile, useSaveHealthProfile } from "./use-agent-health";
import { AccessibleDialog, Button, Skeleton, getErrorMessage } from "./deps";
import { measureLabel } from "./format";
import { Field, NumberField, OverrideRows, ThresholdRows, Violations } from "./profile-fields";
import { inputClass, profileMeasureCatalog, unmatchedViolations } from "./profile-utils";

export interface ProfileMeasureInfo {
  units: Record<string, MeasureUnit>;
  labels: Record<string, string>;
}

type SloKey = "agentTurnSuccess" | "apdexTMs" | "minTurnsForBurnAlert";

/** A field holding text that is not a number blocks the save: NaN would serialise as null. */
function hasInvalidNumber(p: HealthProfile): boolean {
  const nums = [
    ...Object.values(p.thresholds).flatMap((t) => [t.green, t.red]),
    ...Object.values(p.budgets),
    ...Object.values(p.slo),
  ];
  return nums.some((n) => typeof n === "number" && Number.isNaN(n));
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
  const [badNumber, setBadNumber] = useState(false);

  const catalog = useMemo(
    () => profileMeasureCatalog(info.units, draft, presets.data?.find((p) => p.id === draft.preset)),
    [info.units, draft, presets.data],
  );
  const labels = useMemo(
    () => Object.fromEntries(catalog.ids.map((id) => [id, info.labels[id] ?? measureLabel(t, { id, label: id })])),
    [catalog.ids, info.labels, t],
  );
  const general = unmatchedViolations(violations, draft);
  const addable = catalog.ids.filter((id) => !(id in draft.thresholds));

  const submit = () => {
    setViolations([]);
    if (hasInvalidNumber(draft)) {
      setBadNumber(true);
      return;
    }
    setBadNumber(false);
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
  const setSlo = (key: SloKey, v: number | undefined) => setDraft((d) => ({ ...d, slo: { ...d.slo, [key]: v } }));
  const setBudget = (v: number | undefined) =>
    setDraft((d) => {
      const budgets = { ...d.budgets };
      if (v === undefined) delete budgets.costPerResolvedUsd;
      else budgets.costPerResolvedUsd = v;
      return { ...d, budgets };
    });

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
      {badNumber && (
        <p className="text-sm text-destructive" role="alert" data-testid="health-profile-bad-number">
          {t("health.profile.badNumber", "Some fields are not numbers. Fix them and save again.")}
        </p>
      )}
      {save.isError && violations.length === 0 && (
        <p className="text-sm text-destructive" role="alert">{getErrorMessage(save.error)}</p>
      )}
      {general.length > 0 && (
        <div role="alert" data-testid="health-profile-general-violations">
          <p className="text-sm font-medium text-destructive">{t("health.profile.otherProblems", "Other problems")}</p>
          <ul className="text-sm text-destructive">
            {general.map((v) => (
              <li key={`${v.path}-${v.message}`}>{`${v.path}: ${v.message}`}</li>
            ))}
          </ul>
        </div>
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
          {catalog.ids.map((id) => (
            <label key={id} className="flex items-center gap-1.5 text-xs">
              <input type="checkbox" checked={draft.critical.includes(id)} onChange={() => toggleCritical(id)} data-testid={`health-critical-${id}`} />
              {labels[id]}
            </label>
          ))}
        </div>
        <Violations violations={violations} path="critical" />
      </fieldset>

      <fieldset className="space-y-2">
        <legend className="mb-1 text-xs font-medium">{t("health.profile.thresholds", "Thresholds")}</legend>
        <ThresholdRows profile={draft} onChange={setDraft} units={catalog.units} labels={labels} violations={violations} />
        {addable.length > 0 && (
          <select
            className={inputClass}
            aria-label={t("health.profile.addThreshold", "Add a threshold")}
            value=""
            onChange={(e) => e.target.value && setDraft({ ...draft, thresholds: { ...draft.thresholds, [e.target.value]: {} } })}
            data-testid="health-add-threshold"
          >
            <option value="">{t("health.profile.addThreshold", "Add a threshold")}</option>
            {addable.map((id) => (
              <option key={id} value={id}>{labels[id]}</option>
            ))}
          </select>
        )}
      </fieldset>

      <fieldset className="grid gap-3 sm:grid-cols-2">
        <legend className="mb-1 text-xs font-medium">{t("health.profile.budgetsAndSlo", "Budgets and service level")}</legend>
        <Field label={t("health.profile.costPerResolved", "Cost per resolved session (USD)")} violations={violations} path="budgets.costPerResolvedUsd">
          <NumberField
            label={t("health.profile.costPerResolved", "Cost per resolved session (USD)")}
            testId="health-budget-costPerResolvedUsd"
            value={draft.budgets.costPerResolvedUsd}
            unit="usd"
            onValue={setBudget}
          />
        </Field>
        <Field label={t("health.profile.sloSuccess", "Turn success target (%)")} violations={violations} path="slo.agentTurnSuccess">
          <NumberField label={t("health.profile.sloSuccess", "Turn success target (%)")} value={draft.slo.agentTurnSuccess} unit="ratio" onValue={(v) => setSlo("agentTurnSuccess", v)} />
        </Field>
        <Field label={t("health.profile.sloApdex", "Apdex target time (ms)")} violations={violations} path="slo.apdexTMs">
          <NumberField label={t("health.profile.sloApdex", "Apdex target time (ms)")} value={draft.slo.apdexTMs} unit="ms" onValue={(v) => setSlo("apdexTMs", v)} />
        </Field>
        <Field label={t("health.profile.sloMinTurns", "Minimum turns before a burn alert")} violations={violations} path="slo.minTurnsForBurnAlert">
          <NumberField label={t("health.profile.sloMinTurns", "Minimum turns before a burn alert")} value={draft.slo.minTurnsForBurnAlert} unit="count" onValue={(v) => setSlo("minTurnsForBurnAlert", v)} />
        </Field>
      </fieldset>

      <fieldset>
        <legend className="mb-1 text-xs font-medium">{t("health.profile.overrides", "Temporary overrides")}</legend>
        <p className="mb-1 text-xs text-muted-foreground">
          {t("health.profile.overridesHint", "Ignore a measure for a while, with a reason. An override that has expired is dropped silently.")}
        </p>
        <OverrideRows overrides={draft.overrides} onChange={(overrides) => setDraft({ ...draft, overrides })} measureIds={catalog.ids} labels={labels} violations={violations} />
      </fieldset>

      {reset.isError && <p className="text-sm text-destructive" role="alert" data-testid="health-profile-reset-error">{getErrorMessage(reset.error)}</p>}
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
