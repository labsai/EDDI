import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { LLM_PROVIDERS, LLM_PROVIDER_GROUPS } from "@/lib/api/agent-setup";
import {
  getDefaultBaseUrl,
  getProviderRegions,
  type ProviderRegion,
} from "@/lib/llm-provider-catalog";
import { cn } from "@/lib/utils";

const REGION_FALLBACKS: Record<string, string> = {
  intl: "International",
  cn: "China mainland",
  us: "United States",
};

/**
 * LLM provider `<select>`: providers grouped by kind, labelled by display name.
 *
 * A stored value the catalog does not know (a hand-written or newer config) gets
 * its own "(custom)" option instead of silently rendering as the first provider,
 * so opening and saving a config never rewrites its `type`. An empty value with
 * no `leadingOptions` renders a "Select a provider" placeholder rather than
 * silently showing the first provider.
 *
 * `leadingOptions` slots extra `<option>`s (for instance a "None" or "inherit"
 * choice with `value=""`) ahead of the groups. Give the select an `ariaLabel`
 * wherever there is no visible `<label htmlFor={id}>`.
 *
 * `include` narrows the offered providers — the setup flows pass
 * `isProvisionableBySetup`, since setup-api cannot configure every provider. A
 * group left empty by it is not rendered.
 */
export function ProviderSelect({
  value,
  onChange,
  disabled,
  className,
  testId,
  id,
  ariaLabel,
  leadingOptions,
  include,
}: {
  value: string;
  onChange: (providerId: string) => void;
  disabled?: boolean;
  className?: string;
  testId?: string;
  id?: string;
  ariaLabel?: string;
  leadingOptions?: ReactNode;
  include?: (providerId: string) => boolean;
}) {
  const { t } = useTranslation();
  const offered = include ? LLM_PROVIDERS.filter((p) => include(p.id)) : LLM_PROVIDERS;
  const known = offered.some((p) => p.id === value);
  return (
    <select
      value={value}
      onChange={(e) => onChange(e.target.value)}
      disabled={disabled}
      id={id}
      aria-label={ariaLabel}
      className={className}
      data-testid={testId}
    >
      {leadingOptions}
      {!value && !leadingOptions && (
        <option value="" disabled>
          {t("llmProviders.select", "Select a provider")}
        </option>
      )}
      {!known && value && (
        <option value={value}>
          {t("llmEditor.unknownType", "{{type}} (custom)", { type: value })}
        </option>
      )}
      {LLM_PROVIDER_GROUPS.map((group) => {
        const members = offered.filter((p) => p.group === group.id);
        if (members.length === 0) return null;
        return (
          <optgroup key={group.id} label={t(group.labelKey, group.fallback)}>
            {members.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </optgroup>
        );
      })}
    </select>
  );
}

/**
 * Region picker for providers with more than one endpoint; renders nothing otherwise.
 *
 * `className` styles the `<select>` (it is merged over the defaults, so
 * `h-10 rounded-md` wins); `hideLabel` drops the built-in label for callers that
 * supply their own field chrome, such as the operator form.
 */
export function ProviderRegionSelect({
  provider,
  baseUrl,
  onBaseUrlChange,
  className,
  hideLabel,
  testId = "wizard-region",
}: {
  provider: string;
  baseUrl: string;
  /** Receives the region's URL, or "" for the default region (which needs none). */
  onBaseUrlChange: (baseUrl: string) => void;
  className?: string;
  hideLabel?: boolean;
  testId?: string;
}) {
  const { t } = useTranslation();
  const regions = getProviderRegions(provider);
  if (regions.length < 2) return null;

  const defaultUrl = getDefaultBaseUrl(provider);
  const defaultRegionId = (regions.find((r) => r.baseUrl === defaultUrl) ?? regions[0])?.id;
  const matched: ProviderRegion | undefined = baseUrl
    ? regions.find((r) => r.baseUrl === baseUrl)
    : regions.find((r) => r.id === defaultRegionId);
  const selected = matched ? matched.id : "custom";

  function handle(regionId: string) {
    const region = regions.find((r) => r.id === regionId);
    if (!region) return;
    onBaseUrlChange(region.id === defaultRegionId ? "" : region.baseUrl);
  }

  return (
    <div>
      {!hideLabel && (
        <label
          htmlFor={testId}
          className="mb-1.5 block text-sm font-medium text-foreground"
        >
          {t("llmProviders.region.label", "Region")}
        </label>
      )}
      <select
        id={testId}
        value={selected}
        onChange={(e) => handle(e.target.value)}
        className={cn(
          "w-full rounded-lg border border-input bg-background px-3 py-2.5 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring transition-shadow",
          className,
        )}
        data-testid={testId}
      >
        {regions.map((r) => (
          <option key={r.id} value={r.id}>
            {t(`llmProviders.region.${r.id}`, REGION_FALLBACKS[r.id] ?? r.id)}
          </option>
        ))}
        {!matched && (
          <option value="custom" disabled>
            {t("llmProviders.region.custom", "Custom URL (set below)")}
          </option>
        )}
      </select>
    </div>
  );
}
