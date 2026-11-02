import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { act, renderHook, screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router-dom";
import { type ReactNode } from "react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { Sidebar } from "@/components/layout/sidebar";
import { UpdateBanner } from "@/components/layout/update-banner";
import { AboutDialog } from "@/components/layout/about-dialog";
import { WorkforceSidebar } from "@/components/workforce/workforce-sidebar";
import { LandingPage } from "@/pages/landing-page";
import { App } from "@/app";
import { useDocumentTitle } from "@/hooks/use-document-title";
import { AUTO_UPDATE_CHECK_KEY } from "@/hooks/use-update-check";
import { setBrand } from "@/lib/brand";
import { useOnboarding } from "@/hooks/use-onboarding";

/**
 * The Manager under a white-label brand (white-label plan, Phase 3). The rest
 * of the suite runs unbranded and keeps asserting EDDI; this file sets a test
 * brand the way `/branding/__brand__.js` would and checks that the brand, not
 * EDDI, reaches the logos, the version, the title, the update UI and About.
 */

const ZEBRA = {
  branded: true,
  revision: 3,
  productName: "Zebra Labs",
  shortName: "Zebra",
  tagline: "Stripes for teams",
  poweredBy: false,
  showUpdateCheck: false,
  chat: { accent: null },
};

const LATEST_URL = "https://api.github.com/repos/labsai/EDDI/releases/latest";

beforeEach(() => {
  localStorage.clear();
  useOnboarding.setState({
    completedChapters: new Set(),
    activeChapter: null,
    currentStep: 0,
    offeredChapter: null,
  });
});
afterEach(() => setBrand(undefined));

describe("sidebar logo", () => {
  it("unbranded: the one white EDDI wordmark, inverted on the light sidebar as before", () => {
    renderWithProviders(<Sidebar collapsed={false} onToggle={() => {}} />);
    const logo = screen.getByTestId("sidebar-logo");
    expect(logo).toHaveAttribute("src", "/branding/logo");
    expect(logo).toHaveAttribute("alt", "EDDI");
    expect(logo.className).toContain("invert");
    expect(screen.queryByTestId("sidebar-logo-dark")).not.toBeInTheDocument();
  });

  it("branded: the light and dark logos as uploaded, never inverted", () => {
    setBrand(ZEBRA);
    renderWithProviders(<Sidebar collapsed={false} onToggle={() => {}} />);
    const light = screen.getByTestId("sidebar-logo");
    const dark = screen.getByTestId("sidebar-logo-dark");
    expect(light).toHaveAttribute("src", "/branding/logo");
    expect(dark).toHaveAttribute("src", "/branding/logo-dark");
    for (const img of [light, dark]) {
      expect(img).toHaveAttribute("alt", "Zebra Labs");
      expect(img.className).not.toMatch(/\binvert\b/);
    }
    expect(screen.queryByAltText("EDDI")).not.toBeInTheDocument();
  });

  it("collapsed: the E. monogram unbranded, the brand icon branded", () => {
    const { unmount } = renderWithProviders(<Sidebar collapsed onToggle={() => {}} />);
    expect(screen.getByTestId("sidebar-monogram")).toHaveAttribute("aria-label", "EDDI");
    unmount();

    setBrand(ZEBRA);
    renderWithProviders(<Sidebar collapsed onToggle={() => {}} />);
    expect(screen.queryByTestId("sidebar-monogram")).not.toBeInTheDocument();
    const icon = screen.getByTestId("sidebar-brand-icon");
    expect(icon).toHaveAttribute("src", "/branding/icon");
    expect(icon).toHaveAttribute("alt", "Zebra Labs");
  });

  it("follows a brand applied after the first render", () => {
    renderWithProviders(<Sidebar collapsed={false} onToggle={() => {}} />);
    act(() => setBrand(ZEBRA));
    expect(screen.getByTestId("sidebar-logo")).toHaveAttribute("alt", "Zebra Labs");
  });
});

describe("workforce sidebar and landing page", () => {
  it("name the brand on the wordmark, the icon and the welcome heading", () => {
    setBrand(ZEBRA);
    const { unmount } = renderWithProviders(<WorkforceSidebar collapsed={false} onToggle={() => {}} />);
    expect(screen.getByTestId("workforce-sidebar-logo")).toHaveAttribute("alt", "Zebra Labs");
    unmount();

    renderWithProviders(<WorkforceSidebar collapsed onToggle={() => {}} />);
    expect(screen.getByRole("link", { name: "Zebra Labs" })).toBeInTheDocument();
    expect(screen.getByTestId("workforce-sidebar-icon")).toHaveAttribute("src", "/branding/icon");
  });

  it("landing page", () => {
    setBrand(ZEBRA);
    renderWithProviders(<LandingPage />, { initialRoute: "/welcome" });
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Welcome to Zebra Labs");
    expect(screen.getByTestId("landing-brand-icon")).toHaveAttribute("src", "/branding/icon");
  });
});

describe("version and About", () => {
  it("unbranded: the version links to Updates, and About adds Powered by EDDI", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Sidebar collapsed={false} onToggle={() => {}} />);

    const version = await screen.findByTestId("sidebar-version");
    expect(version).toHaveAttribute("href", "/manage/updates");

    await user.click(screen.getByTestId("sidebar-help"));
    await user.click(screen.getByTestId("help-about"));
    const dialog = screen.getByTestId("about-dialog");
    expect(within(dialog).getByTestId("about-product-name")).toHaveTextContent("EDDI");
    expect(within(dialog).getByTestId("about-powered-by")).toHaveTextContent("Powered by EDDI");
    expect(within(dialog).getByTestId("about-license-link")).toHaveAttribute(
      "href",
      "https://www.apache.org/licenses/LICENSE-2.0",
    );
  });

  it("branded: the version names the brand and opens About, with no Powered-by when switched off", async () => {
    setBrand(ZEBRA);
    const user = userEvent.setup();
    renderWithProviders(<Sidebar collapsed={false} onToggle={() => {}} />);

    const version = await screen.findByTestId("sidebar-version");
    await waitFor(() => expect(version).toHaveTextContent(/^Zebra Labs /));
    expect(version.tagName).toBe("BUTTON");
    expect(version.getAttribute("aria-label")).toContain("About Zebra Labs");

    await user.click(version);
    const dialog = screen.getByTestId("about-dialog");
    expect(dialog).toHaveTextContent("About Zebra Labs");
    expect(within(dialog).getByTestId("about-tagline")).toHaveTextContent("Stripes for teams");
    expect(within(dialog).queryByTestId("about-powered-by")).not.toBeInTheDocument();
    expect(dialog.textContent).not.toMatch(/\bEDDI\b/);
  });

  it("branded with poweredBy on: Powered by EDDI and the license stay", () => {
    setBrand({ ...ZEBRA, poweredBy: true });
    renderWithProviders(<AboutDialog open onOpenChange={() => {}} version="6.6.0" />);
    expect(screen.getByTestId("about-version")).toHaveTextContent("Version 6.6.0");
    expect(screen.getByTestId("about-powered-by")).toHaveTextContent("Powered by EDDI");
  });

  it("says when the version is unknown", () => {
    renderWithProviders(<AboutDialog open onOpenChange={() => {}} version={null} />);
    expect(screen.getByTestId("about-version")).toHaveTextContent("Version unknown");
  });
});

describe("update UI (R5)", () => {
  it("branded: no Updates nav entry, no banner, and no request to GitHub even with auto-check on", async () => {
    setBrand(ZEBRA);
    localStorage.setItem(AUTO_UPDATE_CHECK_KEY, "true");
    let githubCalls = 0;
    server.use(
      http.get(LATEST_URL, () => {
        githubCalls++;
        return HttpResponse.json({ tag_name: "9.9.9" });
      }),
    );

    renderWithProviders(
      <>
        <Sidebar collapsed={false} onToggle={() => {}} />
        <UpdateBanner />
      </>,
    );

    await screen.findByTestId("sidebar-version");
    expect(screen.queryByRole("link", { name: "Updates" })).not.toBeInTheDocument();
    await waitFor(() => expect(screen.queryByTestId("update-banner")).not.toBeInTheDocument());
    expect(githubCalls).toBe(0);
  });

  it("branded with showUpdateCheck: the update UI is back", async () => {
    setBrand({ ...ZEBRA, showUpdateCheck: true });
    localStorage.setItem(AUTO_UPDATE_CHECK_KEY, "true");
    renderWithProviders(
      <>
        <Sidebar collapsed={false} onToggle={() => {}} />
        <UpdateBanner />
      </>,
    );

    expect(screen.getByRole("link", { name: "Updates" })).toHaveAttribute("href", "/manage/updates");
    expect(await screen.findByTestId("update-banner")).toHaveTextContent("Zebra Labs 9.9.9 is available");
  });

  it("branded: /manage/updates sends you to the dashboard", async () => {
    setBrand(ZEBRA);
    renderWithProviders(<App />, { initialRoute: "/manage/updates" });
    await waitFor(() => expect(screen.getByTestId("sidebar")).toBeInTheDocument(), { timeout: 15_000 });
    await waitFor(() => expect(document.querySelector("[data-testid='page-loader']")).toBeNull(), {
      timeout: 15_000,
    });
    expect(screen.queryByTestId("updates-page")).not.toBeInTheDocument();
  });
});

describe("document title", () => {
  function wrapper({ children }: { children: ReactNode }) {
    return <MemoryRouter initialEntries={["/manage/agents"]}>{children}</MemoryRouter>;
  }

  it("names the brand", () => {
    setBrand(ZEBRA);
    renderHook(() => useDocumentTitle(), { wrapper });
    expect(document.title).toBe("Agents — Zebra Labs Manager");
  });
});
