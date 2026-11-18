import { afterEach, describe, expect, it } from "vitest";
import { act, fireEvent, screen } from "@testing-library/react";
import { renderWithProviders } from "@eddi/app-kit/test/test-utils";
import { setBrand } from "@eddi/app-kit/lib/brand";
import { WorkforceSidebar } from "@/components/workforce/workforce-sidebar";

/**
 * Workforce under a white-label brand (white-label plan, Phase 3), set the way
 * `/branding/__brand__.js` would. The Manager's chrome has its own cases in
 * ui/manager (src/components/layout/__tests__/branding.test.tsx).
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
  assets: { logo: true, logoDark: true, icon: true, favicon: false, ogImage: false },
};

afterEach(() => setBrand(undefined));

describe("workforce sidebar", () => {
  it("names the brand on the wordmark and the icon", () => {
    setBrand(ZEBRA);
    const { unmount } = renderWithProviders(<WorkforceSidebar collapsed={false} onToggle={() => {}} />);
    expect(screen.getByTestId("workforce-sidebar-logo")).toHaveAttribute("alt", "Zebra Labs");
    unmount();

    renderWithProviders(<WorkforceSidebar collapsed onToggle={() => {}} />);
    expect(screen.getByRole("link", { name: "Zebra Labs" })).toBeInTheDocument();
    expect(screen.getByTestId("workforce-sidebar-icon")).toHaveAttribute("src", "/branding/icon?r=3");
  });

  it("unbranded: a failed icon falls back to the bundled wordmark once, visible in both themes", () => {
    renderWithProviders(<WorkforceSidebar collapsed onToggle={() => {}} />);
    const icon = screen.getByTestId("workforce-sidebar-icon") as HTMLImageElement;
    expect(icon).toHaveAttribute("src", "/branding/icon");
    fireEvent.error(icon);
    const fallback = icon.getAttribute("src");
    expect(fallback).not.toContain("/branding/icon");
    expect(icon.className).toContain("invert");
    expect(icon.className).toContain("dark:invert-0");
    fireEvent.error(icon);
    expect(icon.getAttribute("src")).toBe(fallback);
  });

  it("a later brand revision gets its own attempt after an earlier failure", () => {
    setBrand(ZEBRA);
    renderWithProviders(<WorkforceSidebar collapsed onToggle={() => {}} />);
    fireEvent.error(screen.getByTestId("workforce-sidebar-icon"));
    expect(screen.getByTestId("workforce-sidebar-icon").tagName).toBe("SPAN");
    act(() => setBrand({ ...ZEBRA, revision: 4 }));
    const icon = screen.getByTestId("workforce-sidebar-icon");
    expect(icon.tagName).toBe("IMG");
    expect(icon).toHaveAttribute("src", "/branding/icon?r=4");
    fireEvent.error(icon);
    expect(screen.getByTestId("workforce-sidebar-icon").tagName).toBe("SPAN");
  });
});
