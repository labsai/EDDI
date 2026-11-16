/**
 * Every locale EDDI's UIs ship a translation for — the Manager and, since phase
 * 3a, the Chat UI too. Single source of truth for both apps' loader maps (the
 * types make a missed locale a compile error there).
 */
export const SUPPORTED_LANGUAGES = ["en", "de", "fr", "es", "ar", "zh", "th", "ja", "ko", "pt", "hi"] as const;

export type SupportedLanguage = (typeof SUPPORTED_LANGUAGES)[number];

/**
 * Right-to-left locales — the RTL subset of what we actually ship, which today
 * is Arabic alone.
 *
 * This deliberately does NOT list `he`, `fa` or `ur`. It used to, and the result
 * was a bug rather than forward-thinking: a browser set to Hebrew detected as
 * `he-IL`, matched here, and got `dir="rtl"` — while i18next, having no `he`
 * bundle, fell back to English. Users saw English text laid out right-to-left,
 * under `lang="he-IL"`, which also told screen readers to pronounce English with
 * Hebrew phonetics.
 *
 * Add a language here when, and only when, its locale file lands.
 */
export const RTL_LANGUAGES: readonly string[] = ["ar"];

export function isRtlLanguage(lang: string, rtl: readonly string[] = RTL_LANGUAGES): boolean {
  return rtl.includes(lang.split("-")[0]!.toLowerCase());
}

/**
 * The first of `candidates` (BCP 47 tags in preference order: `de-AT`, `FR_ca`)
 * whose primary subtag is in `languages`, reduced to that subtag; null when none
 * is. What an app without i18next's language detector uses to pick a locale.
 */
export function matchLanguage<L extends string>(
  candidates: ReadonlyArray<string | null | undefined>,
  languages: readonly L[],
): L | null {
  for (const raw of candidates) {
    if (!raw) continue;
    const primary = raw.trim().toLowerCase().split(/[-_]/)[0];
    const match = languages.find((lang) => lang === primary);
    if (match) return match;
  }
  return null;
}
