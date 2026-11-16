import { afterEach, describe, expect, it, vi } from "vitest";
import {
  FONT_LOADERS,
  ensureLocaleFont,
  resetLocaleFontsForTest,
  scriptFamilyFor,
  type ScriptFamily,
} from "./locale-fonts";
import { SUPPORTED_LANGUAGES } from "./languages";

function fakeLoaders(impl: () => Promise<unknown> = () => Promise.resolve()) {
  const calls: ScriptFamily[] = [];
  const loaders = Object.fromEntries(
    (Object.keys(FONT_LOADERS) as ScriptFamily[]).map((family) => [
      family,
      () => {
        calls.push(family);
        return impl();
      },
    ]),
  ) as Record<ScriptFamily, () => Promise<unknown>>;
  return { loaders, calls };
}

afterEach(() => {
  resetLocaleFontsForTest();
  vi.restoreAllMocks();
});

describe("scriptFamilyFor", () => {
  it.each([
    ["ar", "arabic"],
    ["ar-EG", "arabic"],
    ["th", "thai"],
    ["hi-IN", "devanagari"],
    ["ja", "jp"],
    ["ko-KR", "kr"],
    ["zh", "sc"],
    ["zh-CN", "sc"],
    ["zh-Hans", "sc"],
    ["zh-TW", "tc"],
    ["zh-HK", "tc"],
    ["zh_MO", "tc"],
    ["zh-Hant-SG", "tc"],
  ])("%s needs %s", (tag, family) => {
    expect(scriptFamilyFor(tag)).toBe(family);
  });

  it.each(["en", "de", "fr-CA", "es", "pt-BR", "", undefined, null])(
    "%s is covered by the eager Noto Sans",
    (tag) => {
      expect(scriptFamilyFor(tag)).toBeNull();
    },
  );

  it("covers every shipped locale whose script Noto Sans lacks", () => {
    // A new locale in a new script must get a family here, or its users read
    // the system font for the whole UI.
    const latin = new Set(["en", "de", "fr", "es", "pt"]);
    for (const lang of SUPPORTED_LANGUAGES) {
      if (!latin.has(lang)) expect(scriptFamilyFor(lang), lang).not.toBeNull();
    }
  });
});

describe("ensureLocaleFont", () => {
  it("loads nothing for a Latin locale", async () => {
    const { loaders, calls } = fakeLoaders();
    await ensureLocaleFont("de-DE", loaders);
    expect(calls).toEqual([]);
  });

  it("loads a family once, however often its locale is activated", async () => {
    const { loaders, calls } = fakeLoaders();
    await ensureLocaleFont("ja", loaders);
    await ensureLocaleFont("ja-JP", loaders);
    await ensureLocaleFont("ko", loaders);
    expect(calls).toEqual(["jp", "kr"]);
  });

  it("never rejects, and retries a family whose chunk failed", async () => {
    vi.spyOn(console, "warn").mockImplementation(() => {});
    let fail = true;
    const { loaders, calls } = fakeLoaders(() =>
      fail ? Promise.reject(new Error("404")) : Promise.resolve(),
    );
    await expect(ensureLocaleFont("ar", loaders)).resolves.toBeUndefined();
    fail = false;
    await ensureLocaleFont("ar", loaders);
    expect(calls).toEqual(["arabic", "arabic"]);
  });
});
