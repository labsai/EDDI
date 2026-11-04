import type { BackendModule, i18n as I18n, InitOptions, Module, Newable, ReadCallback } from "i18next";
import { isRtlLanguage, RTL_LANGUAGES } from "./languages";

/** A locale bundle loaded on demand: `() => import("./locales/de.json")`. */
export type LocaleLoader = () => Promise<{ default: Record<string, unknown> }>;

/** `{{productName}}` and friends: interpolation variables every string may use. */
export type DefaultVariables = Record<string, string>;

export interface I18nSetupOptions {
  /** The instance to initialise — the app's default i18next, or a created one. */
  i18n: I18n;
  /** Every locale the app ships. English must be one of them. */
  languages: readonly string[];
  /** The English bundle. It is `fallbackLng`, so it is bundled, never loaded. */
  english: Record<string, unknown>;
  /**
   * One dynamic `import()` per other locale. An explicit map rather than a
   * template-literal import: Vite turns a template literal into a glob and emits
   * a chunk for every file matching it, including anything added later that is
   * not a real locale. Naming them makes the chunk set exactly the locale list,
   * and a typo a type error instead of a 404 at runtime.
   */
  loaders: Readonly<Record<string, LocaleLoader>>;
  /** i18next plugins: a language detector, `initReactI18next`. */
  plugins?: ReadonlyArray<Module | Newable<Module>>;
  /** Start in this language instead of detecting one. */
  lng?: string;
  /** `{{productName}}` / `{{shortName}}` from the deployment's brand, for every string. */
  defaultVariables?: DefaultVariables;
  /** The RTL subset of `languages`; defaults to {@link RTL_LANGUAGES}. */
  rtlLanguages?: readonly string[];
  /** Anything else an app sets differently (detection order, key separators). */
  init?: Omit<InitOptions, "resources" | "lng" | "supportedLngs" | "fallbackLng">;
  /** Where `dir` and `lang` go; null for none. Defaults to the page's `<html>`. */
  root?: HTMLElement | null;
}

/**
 * A minimal i18next backend over a loader map.
 *
 * Using the backend interface rather than a `loadLanguage()` helper is what makes
 * this safe: i18next calls `read` itself whenever it needs a bundle it does not
 * have — on init, on `changeLanguage`, on a fallback lookup. There is no call site
 * that can forget to load first. (`i18next-resources-to-backend` does the same
 * job; this is a few lines and no dependency.)
 */
export function createLazyLocaleBackend(loaders: Readonly<Record<string, LocaleLoader>>): BackendModule {
  return {
    type: "backend",
    // Required by the interface; the loader map is the whole backend.
    init: () => {},
    read: (language: string, _namespace: string, callback: ReadCallback) => {
      const loader = loaders[language];
      if (!loader) {
        // Not an error: English is bundled, and anything else is genuinely
        // absent. `null` data (rather than an Error) lets i18next fall back
        // quietly instead of logging a failure for a language never shipped.
        callback(null, null as never);
        return;
      }
      loader()
        .then((module) => callback(null, module.default as never))
        // A chunk that 404s — a tab held open across a deploy — must not leave
        // the UI blank: report it, and i18next falls back to English rather
        // than rendering raw keys.
        .catch((error: unknown) => callback(error as Error, null as never));
    },
  };
}

/**
 * Point the document at the language actually being rendered.
 *
 * Takes i18next's RESOLVED language, never the detected one. They differ
 * whenever the browser asks for something we do not ship: detection reports
 * `nl-NL`, resolution falls back to `en`, and the text is English. Writing the
 * detected value would mislabel the page for assistive tech, and an unsupported
 * RTL locale would flip the layout for text that is not RTL.
 */
export function syncDocumentLanguage(
  i18n: I18n,
  root: HTMLElement | null | undefined,
  rtl: readonly string[] = RTL_LANGUAGES,
): void {
  if (!root) return;
  const lang = renderedLanguage(i18n);
  root.setAttribute("dir", isRtlLanguage(lang, rtl) ? "rtl" : "ltr");
  root.setAttribute("lang", lang);
}

/** The language whose bundle is actually in use, after fallback. */
export function renderedLanguage(i18n: I18n): string {
  return i18n.resolvedLanguage || i18n.language || "en";
}

/**
 * Initialise `i18n` the way both apps need it: English bundled, every other
 * locale loaded on demand and only that one (`partialBundledLanguages`), region
 * tags matched to the locale we ship (`de-AT` → `de`), the brand's default
 * interpolation variables, and `dir`/`lang` on `<html>` kept on the RESOLVED
 * language as it changes.
 *
 * Resolves once the starting language's bundle is in memory — await it before
 * the first render, or a non-English user sees a flash of English. Never
 * rejects: a failed chunk resolves through i18next's own fallback to English.
 *
 * Not included: fonts (`installLocaleFonts`, which an app opts into) and React
 * bindings (pass `initReactI18next` in `plugins`).
 */
export function setupI18n(options: I18nSetupOptions): Promise<unknown> {
  const { i18n, languages, english, loaders, plugins = [], lng, defaultVariables, init = {} } = options;
  const rtl = options.rtlLanguages ?? RTL_LANGUAGES;
  const root = options.root === undefined ? globalRoot() : options.root;

  i18n.use(createLazyLocaleBackend(loaders));
  for (const plugin of plugins) i18n.use(plugin);

  const ready = i18n.init({
    ...init,
    ...(lng ? { lng } : {}),
    // English is bundled; every other language arrives through the backend.
    resources: { en: { translation: english } },
    // Required whenever `resources` and a backend are combined: without it
    // i18next treats the bundled English as the complete set and never asks
    // the backend for anything.
    partialBundledLanguages: true,
    fallbackLng: "en",
    // Without this, `resolvedLanguage` tracks whatever was detected — including
    // locales with no bundle — and "which language is on screen?" is wrong.
    supportedLngs: [...languages],
    // Detection returns region tags (`de-DE`, `pt-BR`). Without this, `de-DE`
    // is its own language, has no bundle, and falls back to English for a
    // locale we do ship.
    nonExplicitSupportedLngs: true,
    interpolation: {
      // React escapes what it renders, and the server rejects `<` and `>` in
      // brand names, so the default variables stay safe unescaped too.
      escapeValue: false,
      ...init.interpolation,
      defaultVariables: { ...defaultVariables },
    },
  });

  // Right for the first paint, not one tick later: the RESOLVED language is
  // known at init, before its bundle has necessarily arrived.
  syncDocumentLanguage(i18n, root, rtl);
  // The event carries the REQUESTED language, not necessarily the rendered one.
  i18n.on("languageChanged", () => syncDocumentLanguage(i18n, root, rtl));

  return ready.then(
    (value) => value,
    () => undefined,
  );
}

/**
 * Replace the default interpolation variables — a brand saved at runtime.
 * i18next reads them on every `t()`, so replacing them in place reaches new
 * renders, and re-announcing the language makes react-i18next re-render every
 * mounted consumer now.
 */
export function setDefaultVariables(i18n: I18n, variables: DefaultVariables): void {
  // In place: the translator and the interpolator hold this same options object.
  if (i18n.options.interpolation) {
    i18n.options.interpolation.defaultVariables = { ...variables };
  }
  i18n.emit("languageChanged", i18n.language);
}

function globalRoot(): HTMLElement | null {
  return typeof document === "undefined" ? null : document.documentElement;
}
