import { useEffect, useId, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { Ban, Check, ChevronDown, ChevronRight, Info, Search, SlidersHorizontal, X } from "lucide-react";
import {
  PARAMETER_CATALOG,
  evaluateParameter,
  findProvider,
  modelIdFor,
  type ParamGroup,
  type ParameterCatalog,
} from "@/lib/llm-parameter-catalog";
import { ParameterRow } from "./model-settings/parameter-row";
import {
  asStringParams,
  buildGroupViews,
  isDropped,
  isSet,
  isUnavailable,
  isVariableReference,
  matchesFilter,
  paramDomId,
  type GroupView,
} from "./model-settings/model-settings-utils";

export interface ModelSettingsSectionProps {
  /** The provider type (`openai`, `anthropic`, …). */
  type: string | undefined;
  /** The parameters this editor writes. */
  parameters: Readonly<Record<string, string>> | undefined;
  onChange: (next: Record<string, string>) => void;
  readOnly?: boolean;
  /**
   * Parameters inherited from elsewhere — a cascade step runs with the task's
   * parameters under its own. They are evaluated with this editor's values on
   * top, and shown as placeholders, but never written.
   */
  inheritedParameters?: Readonly<Record<string, string>>;
  /** Catalog keys another control on the form edits (the Model section's temperature). */
  excludeKeys?: ReadonlySet<string>;
  /**
   * Those keys' controls, as a CSS selector each: they count towards the
   * summary, and the "will be dropped" link jumps to them like to any other
   * parameter.
   */
  externalControls?: Readonly<Record<string, string>>;
  testIdPrefix?: string;
  catalog?: ParameterCatalog;
}

const EMPTY: Readonly<Record<string, string>> = {};
const NO_KEYS: ReadonlySet<string> = new Set();

/**
 * The catalog-driven "Model settings" panel: every parameter EDDI can send to
 * this provider as a typed, documented control, grouped and searchable, with
 * what the configured model will do with each one.
 *
 * It only manages keys the catalog lists for the provider; everything else
 * stays in the generic parameter grid. Renders nothing for a provider the
 * catalog does not describe.
 */
export function ModelSettingsSection({
  type,
  parameters,
  onChange,
  readOnly,
  inheritedParameters,
  excludeKeys = NO_KEYS,
  externalControls,
  testIdPrefix = "model-settings",
  catalog = PARAMETER_CATALOG,
}: ModelSettingsSectionProps) {
  const { t } = useTranslation();
  const uid = useId().replace(/:/g, "");
  // Read through a string view: a value typed in the JSON tab can be a number or
  // a boolean, and every check here works on text. Writes start from the
  // original map, so keys the user did not touch are saved exactly as they were.
  const own = useMemo(() => asStringParams(parameters), [parameters]);
  const inherited = useMemo(() => asStringParams(inheritedParameters), [inheritedParameters]);
  const effective = useMemo(() => ({ ...inherited, ...own }), [inherited, own]);
  const [filter, setFilter] = useState("");
  const [openOverride, setOpenOverride] = useState<Partial<Record<ParamGroup, boolean>>>({});
  const [focusKey, setFocusKey] = useState<string | null>(null);
  const [showUnavailable, setShowUnavailable] = useState<Partial<Record<ParamGroup, boolean>>>({});

  const provider = findProvider(catalog, type);
  const groups = useMemo(
    () => buildGroupViews(catalog, type, own, effective, excludeKeys),
    [catalog, type, own, effective, excludeKeys],
  );
  const model = modelIdFor(type, effective);

  // A group that opened because it held a value stays open when the value is
  // cleared: collapsing under the user's cursor mid-edit would lose their place.
  useEffect(() => {
    const opened = groups.filter((g) => g.configured > 0).map((g) => g.group);
    setOpenOverride((o) => {
      const missing = opened.filter((g) => o[g] === undefined);
      return missing.length === 0 ? o : { ...o, ...Object.fromEntries(missing.map((g) => [g, true])) };
    });
  }, [groups]);

  const external = useMemo(
    () =>
      Object.keys(externalControls ?? {})
        .filter((key) => provider?.byKey.has(key))
        .map((key) => ({
          key,
          configured: isSet(own[key]),
          verdict: evaluateParameter(catalog, type, key, effective),
        })),
    [externalControls, provider, own, catalog, type, effective],
  );

  const all = [
    ...groups.flatMap((g) => g.params.map((p) => ({ key: p.param.key, configured: p.configured, verdict: p.verdict }))),
    ...external,
  ];
  const configuredCount = all.filter((p) => p.configured).length;
  // A rejected value the backend sends under another key is not dropped.
  const dropped = all.filter((p) => p.configured && isDropped(p.verdict));
  const ignoredCount = all.filter((p) => p.configured && p.verdict.state === "ignored").length;

  const domIdFor = (key: string) => paramDomId(`${uid}-${testIdPrefix}`, key);

  useEffect(() => {
    if (!focusKey) return;
    const selector = externalControls?.[focusKey];
    const target = selector
      ? document.querySelector<HTMLElement>(selector)
      : document.getElementById(`${domIdFor(focusKey)}-control`);
    // An external control's row is its own wrapper (the Field around it, where its verdict note sits).
    const row = selector ? (target?.parentElement ?? null) : document.getElementById(domIdFor(focusKey));
    row?.scrollIntoView?.({ block: "center", behavior: "smooth" });
    // The control itself when it can take focus; a disabled one (rejected for
    // every value) hands it to the row's Remove button instead.
    const focusable = (el: Element | null | undefined) =>
      el instanceof HTMLElement && !(el as HTMLInputElement).disabled && el.matches("input,select,textarea,button")
        ? el
        : null;
    const control = focusable(target) ?? focusable(target?.querySelector("input,select,textarea"));
    const fallback = row?.querySelector<HTMLElement>("[data-focus-fallback]") ?? row;
    (control ?? fallback)?.focus?.();
    setFocusKey(null);
    // domIdFor is derived from stable values; re-running on it would loop.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [focusKey, externalControls]);

  if (!provider) return null;

  const setValue = (key: string, value: string | undefined) => {
    const next: Record<string, string> = { ...(parameters ?? EMPTY) };
    if (value === undefined) delete next[key];
    else next[key] = value;
    onChange(next);
  };

  const jumpTo = (key: string) => {
    const group = groups.find((g) => g.params.some((p) => p.param.key === key));
    if (group) {
      setFilter("");
      setOpenOverride((o) => ({ ...o, [group.group]: true }));
      setShowUnavailable((o) => ({ ...o, [group.group]: true }));
    }
    setFocusKey(key);
  };

  const filtering = filter.trim() !== "";
  const visibleGroups: { view: GroupView; params: GroupView["params"] }[] = groups
    .map((view) => ({ view, params: filtering ? view.params.filter((p) => matchesFilter(p.param, filter)) : view.params }))
    .filter((g) => g.params.length > 0);

  return (
    <div className="@container/settings space-y-3" data-testid={testIdPrefix} data-provider={provider.type}>
      {/* Summary + filter */}
      <div className="flex flex-col gap-2 @xl/settings:flex-row @xl/settings:items-center">
        <p className="flex min-w-0 flex-1 flex-wrap items-center gap-x-2 gap-y-1 text-[11px] text-muted-foreground" aria-live="polite" data-testid={`${testIdPrefix}-summary`}>
          <SlidersHorizontal className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
          <span data-testid={`${testIdPrefix}-configured-count`} data-count={configuredCount}>
            {configuredCount === 0
              ? t("llmEditor.modelSettings.noneConfigured", "No settings configured: the provider's defaults apply")
              : t("llmEditor.modelSettings.configured", { count: configuredCount, defaultValue: "{{count}} setting configured", defaultValue_other: "{{count}} settings configured" })}
          </span>
          {dropped.length > 0 && model && (
            <>
              <span aria-hidden="true">·</span>
              <button
                type="button"
                onClick={() => jumpTo(dropped[0]!.key)}
                className="rounded font-semibold text-destructive dark:text-red-400 underline-offset-2 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                data-testid={`${testIdPrefix}-dropped`}
                data-count={dropped.length}
              >
                {t("llmEditor.modelSettings.dropped", { count: dropped.length, model, defaultValue: "{{count}} will be dropped for {{model}}", defaultValue_other: "{{count}} will be dropped for {{model}}" })}
              </button>
            </>
          )}
          {ignoredCount > 0 && model && (
            <>
              <span aria-hidden="true">·</span>
              <span className="text-warning" data-testid={`${testIdPrefix}-ignored`} data-count={ignoredCount}>
                {t("llmEditor.modelSettings.ignored", { count: ignoredCount, model, defaultValue: "{{count}} has no effect on {{model}}", defaultValue_other: "{{count}} have no effect on {{model}}" })}
              </span>
            </>
          )}
          {!model && (
            <>
              <span aria-hidden="true">·</span>
              <span>{t("llmEditor.modelSettings.noModel", "Set a model to see which settings it accepts")}</span>
            </>
          )}
        </p>
        <div className="relative @xl/settings:w-56">
          <Search className="pointer-events-none absolute start-2 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
          <input
            type="search"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            placeholder={t("llmEditor.modelSettings.filterPlaceholder", "Filter settings…")}
            aria-label={t("llmEditor.modelSettings.filter", "Filter model settings")}
            className="h-8 w-full rounded-md border border-input bg-background ps-7 pe-7 text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring [&::-webkit-search-cancel-button]:hidden"
            data-testid={`${testIdPrefix}-filter`}
          />
          {filtering && (
            <button
              type="button"
              onClick={() => setFilter("")}
              className="absolute end-1.5 top-1/2 -translate-y-1/2 rounded p-0.5 text-muted-foreground hover:text-foreground"
              aria-label={t("llmEditor.modelSettings.clearFilter", "Clear filter")}
            >
              <X className="h-3 w-3" aria-hidden="true" />
            </button>
          )}
        </div>
      </div>

      {isVariableReference(model) && (
        <p className="flex items-start gap-1.5 rounded-md border border-border bg-muted/40 px-2 py-1.5 text-[11px] text-muted-foreground" data-testid={`${testIdPrefix}-variable-model`}>
          <Info className="mt-px h-3.5 w-3.5 shrink-0" aria-hidden="true" />
          {t(
            "llmEditor.modelSettings.variableModel",
            "The model is a variable reference ({{model}}), so the checks below cannot know it yet; EDDI applies the model-specific rules once the variable is resolved.",
            { model },
          )}
        </p>
      )}

      {visibleGroups.length === 0 && (
        <p className="rounded-lg border border-dashed border-border px-3 py-4 text-center text-xs text-muted-foreground" data-testid={`${testIdPrefix}-no-match`}>
          {t("llmEditor.modelSettings.noMatch", "No setting matches “{{filter}}”.", { filter: filter.trim() })}
        </p>
      )}

      {visibleGroups.map(({ view, params }) => {
        const open = filtering || (openOverride[view.group] ?? (view.hasCommon || view.configured > 0));
        const panelId = `${uid}-${testIdPrefix}-group-${view.group}`;
        // An inherited value (a cascade step's task parameters) is live, so it stays visible.
        const hidden = (p: GroupView["params"][number]) => !filtering && isUnavailable(p) && !isSet(inherited[p.param.key]);
        const unavailable = params.filter(hidden);
        const shown = params.filter((p) => !hidden(p));
        const unavailableOpen = showUnavailable[view.group] ?? false;
        const unavailableId = `${panelId}-unavailable`;
        const totalLabel = t("llmEditor.modelSettings.groupTotal", "Settings in this group: {{count}}", { count: params.length });
        const configuredLabel = t("llmEditor.modelSettings.groupConfigured", "Configured: {{count}}", { count: view.configured });
        const rejectedLabel = t("llmEditor.modelSettings.groupRejected", "Rejected by the model: {{count}}", { count: view.rejectedConfigured });
        return (
          <section key={view.group} className="rounded-lg border border-border bg-secondary/10" data-testid={`${testIdPrefix}-group-${view.group}`} data-open={open}>
            <h4>
              <button
                type="button"
                onClick={() => setOpenOverride((o) => ({ ...o, [view.group]: !open }))}
                aria-expanded={open}
                aria-controls={panelId}
                disabled={filtering}
                className="flex w-full items-center gap-2 rounded-lg px-2 py-2 @lg/settings:px-3 text-start text-xs font-semibold text-foreground hover:bg-secondary/40 focus:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-default disabled:hover:bg-transparent"
                data-testid={`${testIdPrefix}-group-${view.group}-toggle`}
              >
                {open ? <ChevronDown className="h-3.5 w-3.5 text-muted-foreground" aria-hidden="true" /> : <ChevronRight className="h-3.5 w-3.5 text-muted-foreground rtl:rotate-180" aria-hidden="true" />}
                <span className="flex-1">{groupLabel(t, view.group)}</span>
                {/* Each count says what it counts: an icon, a tooltip, and the full
                    sentence for screen readers. */}
                <span className="text-[10px] font-normal text-muted-foreground" title={totalLabel} data-testid={`${testIdPrefix}-group-${view.group}-total`}>
                  <span className="sr-only">{totalLabel}</span>
                  <span aria-hidden="true">{params.length}</span>
                </span>
                {view.configured > 0 && (
                  <span
                    className="inline-flex items-center gap-0.5 rounded-full bg-primary/15 px-1.5 text-[10px] font-semibold text-foreground"
                    title={configuredLabel}
                    data-testid={`${testIdPrefix}-group-${view.group}-count`}
                  >
                    <Check className="h-2.5 w-2.5" aria-hidden="true" />
                    <span className="sr-only">{configuredLabel}</span>
                    <span aria-hidden="true">{view.configured}</span>
                  </span>
                )}
                {view.rejectedConfigured > 0 && (
                  <span
                    className="inline-flex items-center gap-0.5 rounded-full bg-destructive/15 px-1.5 text-[10px] font-semibold text-destructive dark:text-red-400"
                    title={rejectedLabel}
                    data-testid={`${testIdPrefix}-group-${view.group}-rejected`}
                  >
                    <Ban className="h-2.5 w-2.5" aria-hidden="true" />
                    <span className="sr-only">{rejectedLabel}</span>
                    <span aria-hidden="true">{view.rejectedConfigured}</span>
                  </span>
                )}
              </button>
            </h4>
            {open && (
              <div id={panelId} className="space-y-2 px-1.5 pb-2 @lg/settings:px-3 @lg/settings:pb-3">
                {shown.map((p) => (
                  <ParameterRow
                    key={p.param.key}
                    view={p}
                    catalog={catalog}
                    type={type}
                    effective={effective}
                    inherited={inherited[p.param.key]}
                    onChange={(value) => setValue(p.param.key, value)}
                    readOnly={readOnly}
                    domId={domIdFor(p.param.key)}
                    testIdPrefix={`${testIdPrefix}-param`}
                  />
                ))}
                {unavailable.length > 0 && (
                  <div className="space-y-2">
                    <button
                      type="button"
                      onClick={() => setShowUnavailable((o) => ({ ...o, [view.group]: !unavailableOpen }))}
                      aria-expanded={unavailableOpen}
                      aria-controls={unavailableId}
                      className="flex w-full items-center gap-1.5 rounded-md px-2 py-1 text-start text-[11px] text-muted-foreground hover:bg-secondary/40 hover:text-foreground focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                      data-testid={`${testIdPrefix}-group-${view.group}-unavailable-toggle`}
                      data-count={unavailable.length}
                    >
                      {unavailableOpen ? <ChevronDown className="h-3 w-3" aria-hidden="true" /> : <ChevronRight className="h-3 w-3 rtl:rotate-180" aria-hidden="true" />}
                      {t("llmEditor.modelSettings.unavailable", {
                        count: unavailable.length,
                        model: model ?? "",
                        defaultValue: "{{count}} setting that {{model}} rejects or ignores",
                        defaultValue_other: "{{count}} settings that {{model}} rejects or ignores",
                      })}
                    </button>
                    {unavailableOpen && (
                      <div id={unavailableId} className="space-y-2">
                        {unavailable.map((p) => (
                          <ParameterRow
                            key={p.param.key}
                            view={p}
                            catalog={catalog}
                            type={type}
                            effective={effective}
                            inherited={inherited[p.param.key]}
                            onChange={(value) => setValue(p.param.key, value)}
                            readOnly={readOnly}
                            domId={domIdFor(p.param.key)}
                            testIdPrefix={`${testIdPrefix}-param`}
                          />
                        ))}
                      </div>
                    )}
                  </div>
                )}
              </div>
            )}
          </section>
        );
      })}
    </div>
  );
}

type TFunction = ReturnType<typeof useTranslation>["t"];

/** Static calls, so the i18n gate sees every key. */
function groupLabel(t: TFunction, group: ParamGroup): string {
  switch (group) {
    case "model":
      return t("llmEditor.modelSettings.group.model", "Model");
    case "sampling":
      return t("llmEditor.modelSettings.group.sampling", "Sampling");
    case "output":
      return t("llmEditor.modelSettings.group.output", "Output");
    case "reasoning":
      return t("llmEditor.modelSettings.group.reasoning", "Reasoning");
    case "tools":
      return t("llmEditor.modelSettings.group.tools", "Tools");
    case "caching":
      return t("llmEditor.modelSettings.group.caching", "Caching");
    case "safety":
      return t("llmEditor.modelSettings.group.safety", "Safety");
    case "connection":
      return t("llmEditor.modelSettings.group.connection", "Connection");
    case "advanced":
      return t("llmEditor.modelSettings.group.advanced", "Advanced");
  }
}
