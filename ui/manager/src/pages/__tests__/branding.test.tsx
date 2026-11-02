import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderPage, userEvent } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { BrandingPage } from "@/pages/branding";
import { getBrand, setBrand } from "@/lib/brand";
import { UNBRANDED_COLORS } from "@/lib/brand-css";
import {
  MOCK_BRAND_CONFLICT_TRIGGER,
  MOCK_BRAND_LOCKED_KEY,
  mockBrandScript,
} from "@/test/mocks/branding-handlers";

vi.mock("sonner", () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
}));

function renderBranding() {
  return renderPage("/manage/branding", <BrandingPage />);
}

/** Run what the re-fetched `/branding/__brand__.js` would run, then fire its load. */
function completeBrandReload() {
  const script = document.head.querySelector<HTMLScriptElement>("script[data-brand-reload]");
  if (!script) throw new Error("no brand reload was started");
  new Function("window", mockBrandScript())(window);
  act(() => {
    script.dispatchEvent(new Event("load"));
  });
  return script;
}

const PNG = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0]);

beforeEach(() => {
  sessionStorage.removeItem(MOCK_BRAND_LOCKED_KEY);
});
afterEach(() => {
  document.head.querySelectorAll("script[data-brand-reload], link").forEach((el) => el.remove());
  delete window.__EDDI_BRAND__;
  setBrand(undefined);
  sessionStorage.removeItem(MOCK_BRAND_LOCKED_KEY);
});

describe("BrandingPage — editing", () => {
  it("loads the unbranded state with the defaults as placeholders", async () => {
    renderBranding();
    const name = await screen.findByTestId("branding-productName");
    expect(name).toHaveValue("");
    expect(name).toHaveAttribute("placeholder", "EDDI");
    expect(screen.getByTestId("branding-source")).toHaveTextContent("Built-in defaults");
    expect(screen.getByTestId("branding-save-btn")).toBeDisabled();
    expect(screen.queryByTestId("branding-locked")).not.toBeInTheDocument();
  });

  it("flags what the server would refuse before sending, and blocks Save", async () => {
    const user = userEvent.setup();
    renderBranding();
    await user.type(await screen.findByTestId("branding-productName"), "Acme <b>");
    expect(screen.getByTestId("branding-productName-error")).toBeInTheDocument();
    await user.type(screen.getByTestId("branding-color-primary"), "red;}");
    expect(screen.getByTestId("branding-color-primary-error")).toBeInTheDocument();
    expect(screen.getByTestId("branding-save-btn")).toBeDisabled();

    await user.type(screen.getByTestId("branding-shortName"), "ThirteenChars");
    expect(screen.getByTestId("branding-shortName-error")).toHaveTextContent("12");
  });

  it("shows the contrast of the primary pair as it is typed", async () => {
    renderBranding();
    fireEvent.change(await screen.findByTestId("branding-color-primary"), { target: { value: "#eab308" } });
    fireEvent.change(screen.getByTestId("branding-color-primaryForeground"), { target: { value: "#ffffff" } });
    expect(screen.getByTestId("branding-contrast-light")).toHaveAttribute("data-ok", "false");
    fireEvent.change(screen.getByTestId("branding-color-primary"), { target: { value: "#0f766e" } });
    expect(screen.getByTestId("branding-contrast-light")).toHaveAttribute("data-ok", "true");
    // Dark follows light while it is unset.
    expect(screen.getByTestId("branding-contrast-dark")).toHaveAttribute("data-ok", "true");
  });

  it("shows an empty colour's fallback in its swatch, never black", async () => {
    renderBranding();
    const picker = (key: string) => screen.getByTestId(`branding-color-${key}-picker`);
    await screen.findByTestId("branding-color-primary");
    // Nothing set: the unbranded defaults — the Manager's amber pair, the chat's own accent.
    expect(picker("primary")).toHaveValue(UNBRANDED_COLORS.primary);
    expect(picker("darkPrimary")).toHaveValue(UNBRANDED_COLORS.primary);
    expect(picker("darkPrimaryForeground")).toHaveValue(UNBRANDED_COLORS.primaryForeground);
    expect(picker("chatAccent")).toHaveValue(UNBRANDED_COLORS.chatAccent);

    fireEvent.change(screen.getByTestId("branding-color-primary"), { target: { value: "#0F766E" } });
    fireEvent.change(screen.getByTestId("branding-color-primaryForeground"), { target: { value: "#fff" } });
    // Dark follows light and the accent follows the primary while they are empty.
    expect(picker("darkPrimary")).toHaveValue("#0f766e");
    expect(picker("darkPrimaryForeground")).toHaveValue("#ffffff");
    expect(picker("chatAccent")).toHaveValue("#0f766e");
    expect(picker("darkPrimary").className).toContain("opacity-40");

    fireEvent.change(screen.getByTestId("branding-color-darkPrimary"), { target: { value: "#2dd4bf" } });
    expect(picker("darkPrimary")).toHaveValue("#2dd4bf");
    expect(picker("darkPrimary").className).not.toContain("opacity-40");
  });

  it("previews the colours in both modes with the brand.css formulas", async () => {
    renderBranding();
    fireEvent.change(await screen.findByTestId("branding-color-primary"), { target: { value: "#0f766e" } });
    fireEvent.change(screen.getByTestId("branding-color-darkPrimary"), { target: { value: "#2dd4bf" } });
    const light = screen.getByTestId("brand-preview-light");
    const dark = screen.getByTestId("brand-preview-dark");
    expect(light.style.getPropertyValue("--color-primary")).toBe("#0f766e");
    expect(dark.style.getPropertyValue("--color-primary")).toBe("#2dd4bf");
    expect(light.style.getPropertyValue("--color-brand-50")).toBe("color-mix(in oklch,var(--color-primary) 6%,white)");
    expect(light.className).toContain("brand-preview-light");
  });

  it("saves with the revision it read and applies the new brand to this tab without a reload", async () => {
    const user = userEvent.setup();
    let body: Record<string, unknown> | null = null;
    server.events.on("request:start", async ({ request }) => {
      if (request.method === "PUT" && new URL(request.url).pathname === "/administration/branding") {
        body = (await request.clone().json()) as Record<string, unknown>;
      }
    });
    renderBranding();
    await user.type(await screen.findByTestId("branding-productName"), "Acme Assist");
    fireEvent.change(screen.getByTestId("branding-color-primary"), { target: { value: "#0f766e" } });
    await user.click(screen.getByTestId("branding-save-btn"));

    await waitFor(() => expect(screen.getByTestId("branding-source")).toHaveTextContent("Saved on this page"));
    server.events.removeAllListeners();
    expect(body).toMatchObject({ revision: 0, productName: "Acme Assist", shortName: null });
    expect((body as unknown as { colors: Record<string, unknown> }).colors.primary).toBe("#0f766e");

    // The live update: brand.css swapped, __brand__.js re-run, setBrand fed.
    const script = completeBrandReload();
    expect(script.getAttribute("src")).toBe("/branding/__brand__.js?r=1");
    expect(getBrand()).toMatchObject({ branded: true, revision: 1, productName: "Acme Assist" });
    expect(document.head.querySelector('link[href^="/branding/brand.css"]')?.getAttribute("href")).toBe(
      "/branding/brand.css?r=1",
    );
    // The form is now based on the saved document.
    expect(screen.getByTestId("branding-save-btn")).toBeDisabled();
  });

  it("shows the server's violations on the field they name", async () => {
    const user = userEvent.setup();
    server.use(
      http.put("*/administration/branding", () =>
        HttpResponse.json(
          { error: "The brand has invalid values.", violations: [{ field: "tagline", message: "is not allowed here" }] },
          { status: 400 },
        ),
      ),
    );
    renderBranding();
    await user.type(await screen.findByTestId("branding-tagline"), "Fine on the client");
    await user.click(screen.getByTestId("branding-save-btn"));
    expect(await screen.findByTestId("branding-tagline-error")).toHaveTextContent("is not allowed here");
    expect(screen.getByTestId("branding-save-error")).toHaveTextContent("The brand has invalid values.");
  });

  it("shows the contrast warnings the server returns with an accepted save", async () => {
    const user = userEvent.setup();
    renderBranding();
    fireEvent.change(await screen.findByTestId("branding-color-primary"), { target: { value: "#eab308" } });
    fireEvent.change(screen.getByTestId("branding-color-primaryForeground"), { target: { value: "#ffffff" } });
    await user.click(screen.getByTestId("branding-save-btn"));
    expect(await screen.findByTestId("branding-warnings")).toHaveTextContent("below the 4.5:1");
  });
});

describe("BrandingPage — conflicts, reset and lock", () => {
  it("does not overwrite a newer save: a 409 offers Reload latest, which rebases the form", async () => {
    const user = userEvent.setup();
    renderBranding();
    await user.type(await screen.findByTestId("branding-tagline"), MOCK_BRAND_CONFLICT_TRIGGER);
    await user.click(screen.getByTestId("branding-save-btn"));

    const notice = await screen.findByTestId("branding-conflict");
    expect(notice).toHaveTextContent("revision 1");
    expect(screen.getByTestId("branding-save-btn")).toBeDisabled();

    await user.click(within(notice).getByTestId("branding-reload-latest"));
    await waitFor(() => expect(screen.queryByTestId("branding-conflict")).not.toBeInTheDocument());
    expect(screen.getByTestId("branding-tagline")).toHaveValue("");

    // The next save goes out with the new revision and succeeds.
    await user.type(screen.getByTestId("branding-productName"), "Acme");
    await user.click(screen.getByTestId("branding-save-btn"));
    await waitFor(() => expect(screen.getByTestId("branding-updated")).toHaveTextContent("revision 2"));
  });

  it("resets only after confirmation", async () => {
    const user = userEvent.setup();
    renderBranding();
    await user.type(await screen.findByTestId("branding-productName"), "Acme");
    await user.click(screen.getByTestId("branding-save-btn"));
    await waitFor(() => expect(screen.getByTestId("branding-productName")).toHaveValue("Acme"));
    await waitFor(() => expect(screen.getByTestId("branding-save-btn")).toBeDisabled());

    await user.click(screen.getByTestId("branding-reset-btn"));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Reset to defaults" }));
    await waitFor(() => expect(screen.getByTestId("branding-productName")).toHaveValue(""));
    expect(screen.getByTestId("branding-source")).toHaveTextContent("Built-in defaults");
  });

  it("locked: everything read-only, with the property and the source named", async () => {
    sessionStorage.setItem(MOCK_BRAND_LOCKED_KEY, "true");
    renderBranding();
    const notice = await screen.findByTestId("branding-locked");
    expect(notice).toHaveTextContent("eddi.branding.locked=true");
    expect(notice).toHaveTextContent("Configuration properties");
    expect(screen.getByTestId("branding-productName")).toBeDisabled();
    expect(screen.getByTestId("branding-productName")).toHaveValue("Zebra Labs");
    expect(screen.getByTestId("branding-color-primary")).toBeDisabled();
    expect(screen.getByTestId("branding-powered-by")).toBeDisabled();
    expect(screen.queryByTestId("branding-save-btn")).not.toBeInTheDocument();
    expect(screen.queryByTestId("branding-reset-btn")).not.toBeInTheDocument();
    expect(screen.queryByTestId("brand-asset-logo-remove")).not.toBeInTheDocument();
    expect(screen.getByTestId("brand-asset-logo-drop")).toHaveAttribute("aria-disabled", "true");
    // Downloading the login theme is a read, so it stays available.
    expect(screen.getByTestId("branding-login-theme-download")).toBeEnabled();
  });
});

describe("BrandingPage — login theme", () => {
  afterEach(() => vi.restoreAllMocks());

  it("downloads the Keycloak theme ZIP under its file name", async () => {
    const user = userEvent.setup();
    const createObjectURL = vi.fn((blob: Blob) => {
      expect(blob.size).toBeGreaterThan(0);
      return "blob:theme";
    });
    Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() });
    let saved: string | null = null;
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) {
      saved = this.download;
    });
    renderBranding();
    await user.click(await screen.findByTestId("branding-login-theme-download"));
    await waitFor(() => expect(saved).toBe("brand-keycloak-theme.zip"));
    expect(createObjectURL).toHaveBeenCalledTimes(1);
  });

  it("says that unsaved edits are not in it", async () => {
    renderBranding();
    expect(await screen.findByTestId("branding-login-theme")).toBeInTheDocument();
    expect(screen.queryByTestId("branding-login-theme-unsaved")).not.toBeInTheDocument();
    fireEvent.change(screen.getByTestId("branding-productName"), { target: { value: "Acme" } });
    expect(screen.getByTestId("branding-login-theme-unsaved")).toBeInTheDocument();
  });

  it("reports a failed download", async () => {
    const { toast } = await import("sonner");
    server.use(
      http.get("*/administration/branding/keycloak-theme.zip", () => new HttpResponse(null, { status: 403, statusText: "Forbidden" })),
    );
    const user = userEvent.setup();
    renderBranding();
    await user.click(await screen.findByTestId("branding-login-theme-download"));
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith(expect.stringContaining("403")));
  });
});

describe("BrandingPage — images", () => {
  async function upload(slot: string, file: File) {
    const input = (await screen.findByTestId(`brand-asset-${slot}-input`)) as HTMLInputElement;
    fireEvent.change(input, { target: { files: [file] } });
  }

  it("uploads, shows the slot as uploaded, and keeps unsaved text edits on the new revision", async () => {
    const user = userEvent.setup();
    renderBranding();
    await user.type(await screen.findByTestId("branding-productName"), "Acme");
    expect(await screen.findByTestId("brand-asset-logo-note")).toHaveTextContent("Acme");

    await upload("logo", new File([PNG], "logo.png", { type: "image/png" }));
    expect(await screen.findByTestId("brand-asset-logo-uploaded")).toBeInTheDocument();
    expect(screen.getByTestId("brand-asset-logo-image")).toHaveAttribute("src", "/branding/logo?r=1");
    expect(screen.queryByTestId("brand-asset-logo-note")).not.toBeInTheDocument();
    // The edit survived the upload, and saving it does not conflict with it.
    expect(screen.getByTestId("branding-productName")).toHaveValue("Acme");
    await user.click(screen.getByTestId("branding-save-btn"));
    await waitFor(() => expect(screen.getByTestId("branding-updated")).toHaveTextContent("revision 2"));
    expect(screen.queryByTestId("branding-conflict")).not.toBeInTheDocument();

    await user.click(screen.getByTestId("brand-asset-logo-remove"));
    expect(await screen.findByTestId("brand-asset-logo-default")).toBeInTheDocument();
  });

  it("explains an unsafe SVG (400)", async () => {
    renderBranding();
    await upload("icon", new File(['<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>'], "x.svg", { type: "image/svg+xml" }));
    expect(await screen.findByTestId("brand-asset-icon-error")).toHaveTextContent("The image was refused");
  });

  it("explains a type the slot does not take (415)", async () => {
    renderBranding();
    await upload("logo", new File(["just text"], "logo.txt", { type: "text/plain" }));
    expect(await screen.findByTestId("brand-asset-logo-error")).toHaveTextContent("not accepted here");
  });

  it("refuses an image over 512 KB before sending it, and maps a server 413 the same way", async () => {
    renderBranding();
    const big = new File([new Uint8Array(512 * 1024 + 1)], "big.png", { type: "image/png" });
    await upload("logo", big);
    expect(await screen.findByTestId("brand-asset-logo-error")).toHaveTextContent("512 KB");

    server.use(
      http.put("*/administration/branding/assets/:slot", () =>
        HttpResponse.json({ error: "too large" }, { status: 413 }),
      ),
    );
    await upload("icon", new File([PNG], "icon.png", { type: "image/png" }));
    expect(await screen.findByTestId("brand-asset-icon-error")).toHaveTextContent("512 KB");
  });

  it("explains a locked brand (409) on an image write", async () => {
    renderBranding();
    await screen.findByTestId("brand-asset-favicon-input");
    sessionStorage.setItem(MOCK_BRAND_LOCKED_KEY, "true");
    await upload("favicon", new File([PNG], "favicon.png", { type: "image/png" }));
    expect(await screen.findByTestId("brand-asset-favicon-error")).toHaveTextContent("locked");
  });
});
