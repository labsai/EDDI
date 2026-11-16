import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  PII_COUNTRIES,
  PII_REGIONS,
  PII_UNIVERSAL_KINDS,
  enabledPiiCountries,
  enabledPiiKinds,
  withPiiCountries,
  withPiiKinds,
  type PiiRegion,
} from "./guardrails";
import type { GuardrailConfig } from "./types";

const LEGEND = "mb-1 block text-[10px] font-semibold uppercase tracking-wider text-muted-foreground";
const CHECKBOX = "h-3.5 w-3.5 rounded border-input accent-primary";

/**
 * The `pii-redaction` selection: the kinds of any country, then the countries
 * grouped by region. A whole country goes into `countries`; a single identifier
 * of a country goes into `patterns`, next to the universal kinds.
 */
export function PiiKindsPicker({
  value,
  onChange,
  readOnly,
}: {
  value: GuardrailConfig;
  onChange: (next: GuardrailConfig) => void;
  readOnly: boolean;
}) {
  const { t, i18n } = useTranslation();
  const kinds = enabledPiiKinds(value);
  const countries = enabledPiiCountries(value);
  const regionNames = useMemo(() => {
    try {
      return new Intl.DisplayNames([i18n.language, "en"], { type: "region" });
    } catch {
      return null;
    }
  }, [i18n.language]);

  // Regions with a selection start open; after that each stays as the user left it.
  const [openRegions, setOpenRegions] = useState<Set<PiiRegion>>(
    () =>
      new Set(
        PII_REGIONS.filter((region) =>
          PII_COUNTRIES.some(
            (c) => c.region === region && (countries.includes(c.code) || c.kinds.some((k) => kinds.includes(k.kind))),
          ),
        ),
      ),
  );

  const toggleKind = (kind: string, on: boolean) =>
    onChange(withPiiKinds(value, on ? [...kinds, kind] : kinds.filter((k) => k !== kind)));
  const toggleCountry = (code: string, on: boolean) =>
    onChange(withPiiCountries(value, on ? [...countries, code] : countries.filter((c) => c !== code)));
  const toggleRegion = (region: PiiRegion, open: boolean) =>
    setOpenRegions((prev) => {
      if (prev.has(region) === open) return prev;
      const next = new Set(prev);
      if (open) next.add(region);
      else next.delete(region);
      return next;
    });

  const kindBox = (kind: string, label: string, impliedByCountry = false) => {
    const checked = impliedByCountry || kinds.includes(kind);
    return (
      <label key={kind} className="flex items-center gap-1.5 text-xs text-foreground">
        <input
          type="checkbox"
          checked={checked}
          // The last selected kind stays on: an empty list means the standard set.
          disabled={readOnly || impliedByCountry || (checked && kinds.length === 1)}
          onChange={(e) => toggleKind(kind, e.target.checked)}
          className={CHECKBOX}
          data-testid={`guardrail-pii-${kind}`}
        />
        {label}
      </label>
    );
  };

  const universalLabel: Record<(typeof PII_UNIVERSAL_KINDS)[number], string> = {
    email: t("llmEditor.grPiiEmail", "E-mail addresses"),
    phone: t("llmEditor.grPiiPhone", "Phone numbers"),
    creditcard: t("llmEditor.grPiiCard", "Payment card numbers (Luhn-checked)"),
    iban: t("llmEditor.grPiiIban", "IBANs (checksum-validated)"),
    ip_address: t("llmEditor.grPiiIpAddress", "IP addresses (IPv4 and IPv6)"),
    passport_mrz: t("llmEditor.grPiiPassportMrz", "Passport machine-readable zones (any country)"),
    date_of_birth: t("llmEditor.grPiiDateOfBirth", "Dates of birth (next to a keyword such as “born”)"),
  };
  const regionLabel: Record<PiiRegion, string> = {
    europe: t("llmEditor.grPiiRegionEurope", "Europe"),
    americas: t("llmEditor.grPiiRegionAmericas", "Americas"),
    asiaPacific: t("llmEditor.grPiiRegionAsiaPacific", "Asia-Pacific"),
    africa: t("llmEditor.grPiiRegionAfrica", "Africa"),
  };

  return (
    <>
      <fieldset>
        <legend className={LEGEND}>{t("llmEditor.grPiiKinds", "Kinds to find")}</legend>
        <div className="space-y-1">{PII_UNIVERSAL_KINDS.map((kind) => kindBox(kind, universalLabel[kind]))}</div>
      </fieldset>
      <fieldset data-testid="guardrail-pii-countries">
        <legend className={LEGEND}>{t("llmEditor.grPiiCountries", "National identifiers")}</legend>
        <p className="mb-1.5 text-[11px] text-muted-foreground">
          {t(
            "llmEditor.grPiiCountriesHint",
            "Tick a country to find all of its identifiers, or tick single identifiers. Each is checked against its own format and check digit; some also need a keyword such as “PESEL” or “NHS” just before the number.",
          )}
        </p>
        <div className="space-y-1">
          {PII_REGIONS.map((region) => (
            <details
              key={region}
              open={openRegions.has(region)}
              onToggle={(e) => toggleRegion(region, e.currentTarget.open)}
              className="rounded border border-border px-2 py-1"
              data-testid={`guardrail-pii-region-${region}`}
            >
              <summary className="cursor-pointer text-xs font-medium text-foreground">{regionLabel[region]}</summary>
              <div className="mt-1 space-y-1">
                {PII_COUNTRIES.filter((c) => c.region === region).map((country) => {
                  const whole = countries.includes(country.code);
                  return (
                    <div key={country.code} className="flex flex-wrap items-center gap-x-3 gap-y-1">
                      <label className="flex min-w-36 items-center gap-1.5 text-xs font-medium text-foreground">
                        <input
                          type="checkbox"
                          checked={whole}
                          disabled={readOnly}
                          onChange={(e) => toggleCountry(country.code, e.target.checked)}
                          className={CHECKBOX}
                          data-testid={`guardrail-pii-country-${country.code}`}
                        />
                        {regionNames?.of(country.code) ?? country.code}
                      </label>
                      {country.kinds.map((k) => kindBox(k.kind, k.name, whole))}
                    </div>
                  );
                })}
              </div>
            </details>
          ))}
        </div>
      </fieldset>
    </>
  );
}
