import { useTranslation } from "react-i18next";
import { toast } from "sonner";

/**
 * Language switching, shared by the top bar and the mobile navigation drawer.
 *
 * Tolerates a locale chunk that fails to arrive and a user who changes their
 * mind mid-download.
 *
 * Locales are code-split (`src/i18n/config.ts`), so `changeLanguage` now
 * performs a network fetch. Two consequences, both handled here:
 *
 *  - **It can fail without rejecting.** A tab held open across a deploy asks
 *    for a hashed chunk that no longer exists. i18next treats that as a soft
 *    miss: `changeLanguage` RESOLVES, its callback reports `err === null`, and
 *    `i18n.language` is set to the requested code — only `resolvedLanguage`
 *    quietly stays behind on the fallback. So the select would read "Deutsch"
 *    over English text with nothing to explain it. Whether the bundle actually
 *    landed is the one trustworthy signal, hence `applyLanguage`.
 *  - **Two changes can be in flight at once.** Chunks are different sizes, so
 *    picking Thai then Spanish can finish Spanish-then-Thai and leave the user
 *    reading a language they already moved on from. `latestLanguageRequest`
 *    records the most recent pick; an older completion is discarded rather
 *    than applied.
 */
/**
 * The most recent pick, shared by EVERY instance of the hook. The top bar and the
 * mobile drawer each call it, so a per-instance ref let a slow older request from
 * one surface apply after a newer pick made on the other.
 */
const latestLanguageRequest: { current: string | null } = { current: null };

export function useLanguageSwitcher() {
  const { t, i18n } = useTranslation();

  /**
   * Switch to `code`, throwing if its bundle did not actually arrive.
   *
   * English is bundled statically, so its check passes without a fetch.
   */
  async function applyLanguage(code: string) {
    await i18n.changeLanguage(code);
    if (!i18n.hasResourceBundle(code, "translation")) {
      throw new Error(`locale bundle for "${code}" did not load`);
    }
  }

  async function changeLanguage(code: string) {
    const previous = i18n.language;
    latestLanguageRequest.current = code;
    try {
      await applyLanguage(code);
      // A slower earlier request may land after a faster later one. Only the
      // most recent pick is allowed to stand.
      const latest = latestLanguageRequest.current;
      if (latest !== code) await applyLanguage(latest ?? code);
    } catch {
      if (latestLanguageRequest.current !== code) return; // superseded; stay quiet
      // i18next has already moved `language` to the code that failed, which is
      // what the select binds to. Put it back on something that renders.
      latestLanguageRequest.current = previous;
      await i18n.changeLanguage(previous).catch(() => {});
      toast.error(
        t("language.switchFailed", "Could not load that language. Please try again."),
      );
    }
  }

  const languages = [
    { code: "en", label: t("language.en") },
    { code: "de", label: t("language.de") },
    { code: "fr", label: t("language.fr") },
    { code: "es", label: t("language.es") },
    { code: "ar", label: t("language.ar") },
    { code: "zh", label: t("language.zh") },
    { code: "th", label: t("language.th") },
    { code: "ja", label: t("language.ja") },
    { code: "ko", label: t("language.ko") },
    { code: "pt", label: t("language.pt") },
    { code: "hi", label: t("language.hi") },
  ];

  return { language: i18n.language, languages, changeLanguage };
}
