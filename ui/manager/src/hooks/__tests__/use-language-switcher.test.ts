import { afterEach, describe, expect, it, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import i18n from "@/i18n/config";
import { useLanguageSwitcher } from "@/hooks/use-language-switcher";

describe("useLanguageSwitcher — ordering across instances", () => {
  afterEach(() => vi.restoreAllMocks());

  it("a newer pick on one surface wins over a slower older pick on another", async () => {
    const pending: Array<{ code: string; resolve: () => void }> = [];
    vi.spyOn(i18n, "hasResourceBundle").mockReturnValue(true);
    const change = vi.spyOn(i18n, "changeLanguage").mockImplementation(((code: string) =>
      new Promise<void>((resolve) => {
        pending.push({ code, resolve });
      })) as unknown as typeof i18n.changeLanguage);

    // The top bar and the mobile drawer are two instances of the hook.
    const topBar = renderHook(() => useLanguageSwitcher());
    const drawer = renderHook(() => useLanguageSwitcher());

    let older!: Promise<void>;
    let newer!: Promise<void>;
    act(() => {
      older = topBar.result.current.changeLanguage("de");
      newer = drawer.result.current.changeLanguage("fr");
    });
    expect(pending.map((p) => p.code)).toEqual(["de", "fr"]);

    // The newer request lands first, the older one afterwards.
    await act(async () => {
      pending[1]!.resolve();
      await newer;
    });
    await act(async () => {
      pending[0]!.resolve();
      await Promise.resolve();
    });

    // The older completion must not stand: the hook re-applies the latest pick.
    const asked = change.mock.calls.map((c) => c[0]);
    expect(asked).toEqual(["de", "fr", "fr"]);
    await act(async () => {
      pending[pending.length - 1]!.resolve();
      await older;
    });
  });
});
