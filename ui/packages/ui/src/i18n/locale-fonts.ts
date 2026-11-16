/**
 * Script fonts, loaded for the language on screen instead of all at once.
 *
 * ## Why
 *
 * The Manager's `main.tsx` used to import all eight Noto families. The font FILES were
 * already lazy (each `@font-face` carries a `unicode-range`, so the browser only
 * fetches a file when a glyph in its range is drawn), but the DECLARATIONS were
 * not: 476 `@font-face` rules, about 388 KB of the 606 KB main stylesheet, went
 * to every user in every language before the first paint. Most of them are the
 * CJK families, which split each script into ~100 unicode-range slices.
 *
 * Now the main stylesheet carries Noto Sans (Latin, Greek, Cyrillic, the
 * default) and nothing else, and the family a locale needs is imported here, as
 * its own CSS chunk, when that locale becomes active.
 *
 * ## Text in another script
 *
 * A Latin UI can still show Japanese, Arabic or Thai: an agent reply, a user's
 * name, a conversation title. `--font-sans` in `styles/theme.css` still names every
 * Noto family, so where a family's CSS has not been loaded the browser falls
 * through to `system-ui` / `sans-serif`, which on every desktop and mobile OS
 * has a font for these scripts. It renders correctly, in the system's face
 * rather than in Noto. Users who read that script all day pick its locale and
 * get Noto.
 */

import type { i18n as I18n } from "i18next";

/** The script families that are loaded on demand. Noto Sans itself is eager. */
export type ScriptFamily = "arabic" | "thai" | "devanagari" | "jp" | "kr" | "sc" | "tc";

/**
 * One loader per family. An explicit map rather than a template-literal import,
 * for the same reason as the locale loader map (`setup.ts`): the chunk set is
 * exactly what is named here.
 */
export const FONT_LOADERS: Record<ScriptFamily, () => Promise<unknown>> = {
  arabic: () => import("@fontsource-variable/noto-sans-arabic"),
  thai: () => import("@fontsource-variable/noto-sans-thai"),
  devanagari: () => import("@fontsource-variable/noto-sans-devanagari"),
  jp: () => import("@fontsource-variable/noto-sans-jp"),
  kr: () => import("@fontsource-variable/noto-sans-kr"),
  sc: () => import("@fontsource-variable/noto-sans-sc"),
  tc: () => import("@fontsource-variable/noto-sans-tc"),
};

/**
 * The script family a language tag needs, or null for one Noto Sans covers.
 *
 * Takes a BCP 47 tag rather than a bare language because Chinese depends on the
 * rest of it: Taiwan, Hong Kong and Macau, and an explicit `Hant` script, use
 * Traditional characters; everything else (`zh`, `zh-CN`, `zh-SG`, `zh-Hans`)
 * uses Simplified. The apps ship one `zh` locale, but the detected tag —
 * which `i18n.language` keeps — still says which glyphs the reader expects.
 */
export function scriptFamilyFor(tag: string | undefined | null): ScriptFamily | null {
  if (!tag) return null;
  const parts = tag.toLowerCase().split(/[-_]/);
  switch (parts[0]) {
    case "ar":
      return "arabic";
    case "th":
      return "thai";
    case "hi":
      return "devanagari";
    case "ja":
      return "jp";
    case "ko":
      return "kr";
    case "zh":
      return parts.some((p) => p === "hant" || p === "tw" || p === "hk" || p === "mo") ? "tc" : "sc";
    default:
      return null;
  }
}

const requested = new Map<ScriptFamily, Promise<void>>();

/**
 * Load the family `tag` needs, once. Resolves when its stylesheet is in, and
 * never rejects: a chunk that 404s (a tab held open across a deploy) leaves the
 * system fallback in place, which still renders the text — and is retried on
 * the next language change.
 */
export function ensureLocaleFont(
  tag: string | undefined | null,
  loaders: Record<ScriptFamily, () => Promise<unknown>> = FONT_LOADERS,
): Promise<void> {
  const family = scriptFamilyFor(tag);
  if (!family) return Promise.resolve();
  let pending = requested.get(family);
  if (!pending) {
    pending = loaders[family]().then(
      () => undefined,
      (error: unknown) => {
        requested.delete(family);
        console.warn(`[EDDI] could not load the ${family} font; using the system font`, error);
      },
    );
    requested.set(family, pending);
  }
  return pending;
}

/**
 * Load the current language's family now and on every language change.
 * Returns the first load, which the Manager's `main.tsx` awaits next to
 * `i18nReady` so a Japanese user's first paint is already in Noto Sans JP.
 *
 * Opt-in, and separate from `setupI18n`: the families are an app's choice (the
 * Manager loads them; the Chat UI keeps the fonts its page declares), and an app
 * that never calls this never bundles a font chunk.
 */
export function installLocaleFonts(
  i18n: I18n,
  loaders: Record<ScriptFamily, () => Promise<unknown>> = FONT_LOADERS,
): Promise<void> {
  i18n.on("languageChanged", (lng: string) => {
    void ensureLocaleFont(lng, loaders);
  });
  return ensureLocaleFont(i18n.language, loaders);
}

/** Test hook: forget what has been requested. */
export function resetLocaleFontsForTest(): void {
  requested.clear();
}
