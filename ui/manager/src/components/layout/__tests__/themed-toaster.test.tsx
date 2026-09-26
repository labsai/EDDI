import { describe, expect, it, vi } from "vitest";
import { render } from "@testing-library/react";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { ThemedToaster } from "@/components/layout/themed-toaster";

const toasterProps = vi.hoisted(() => ({ last: null as Record<string, unknown> | null }));

vi.mock("sonner", async (importOriginal) => {
  const actual = await importOriginal<typeof import("sonner")>();
  return {
    ...actual,
    Toaster: (props: Record<string, unknown>) => {
      toasterProps.last = props;
      return null;
    },
  };
});

/**
 * Sonner ignores the `dark` class on <html> and defaults to light, so toasts
 * stayed light in dark mode. The toaster now takes the resolved theme.
 */
describe("ThemedToaster", () => {
  it.each(["dark", "light"] as const)("follows the %s theme", (theme) => {
    render(
      <ThemeProvider defaultTheme={theme} storageKey="toaster-test">
        <ThemedToaster />
      </ThemeProvider>,
    );
    expect(toasterProps.last?.theme).toBe(theme);
  });

  it("follows the OS preference in system mode", () => {
    // The test setup's matchMedia reports prefers-color-scheme: dark.
    render(
      <ThemeProvider defaultTheme="system" storageKey="toaster-test">
        <ThemedToaster />
      </ThemeProvider>,
    );
    expect(toasterProps.last?.theme).toBe("dark");
  });
});
