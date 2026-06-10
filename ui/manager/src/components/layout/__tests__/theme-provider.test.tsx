import { describe, it, expect } from "vitest";
import { render, screen, act } from "@testing-library/react";
import { ThemeProvider, useTheme } from "@/components/layout/theme-provider";
import { userEvent } from "@/test/test-utils";

function ThemeConsumer() {
  const { theme, resolvedTheme, setTheme } = useTheme();
  return (
    <div>
      <span data-testid="theme">{theme}</span>
      <span data-testid="resolved">{resolvedTheme}</span>
      <button data-testid="set-dark" onClick={() => setTheme("dark")}>
        Dark
      </button>
      <button data-testid="set-light" onClick={() => setTheme("light")}>
        Light
      </button>
      <button data-testid="set-system" onClick={() => setTheme("system")}>
        System
      </button>
    </div>
  );
}

describe("ThemeProvider", () => {
  it("provides default theme as system", () => {
    render(
      <ThemeProvider>
        <ThemeConsumer />
      </ThemeProvider>
    );
    expect(screen.getByTestId("theme").textContent).toBe("system");
  });

  it("resolves system theme to dark when matchMedia prefers dark", () => {
    render(
      <ThemeProvider>
        <ThemeConsumer />
      </ThemeProvider>
    );
    // Our test setup mocks matchMedia to return matches=true for dark
    expect(screen.getByTestId("resolved").textContent).toBe("dark");
  });

  it("uses defaultTheme prop", () => {
    render(
      <ThemeProvider defaultTheme="dark">
        <ThemeConsumer />
      </ThemeProvider>
    );
    expect(screen.getByTestId("theme").textContent).toBe("dark");
    expect(screen.getByTestId("resolved").textContent).toBe("dark");
  });

  it("changes theme when setTheme is called", async () => {
    const user = userEvent.setup();
    render(
      <ThemeProvider defaultTheme="light">
        <ThemeConsumer />
      </ThemeProvider>
    );

    expect(screen.getByTestId("theme").textContent).toBe("light");
    await user.click(screen.getByTestId("set-dark"));
    expect(screen.getByTestId("theme").textContent).toBe("dark");
    expect(screen.getByTestId("resolved").textContent).toBe("dark");
  });

  it("persists theme to localStorage", async () => {
    const user = userEvent.setup();
    render(
      <ThemeProvider storageKey="test-theme">
        <ThemeConsumer />
      </ThemeProvider>
    );

    await user.click(screen.getByTestId("set-dark"));
    expect(localStorage.getItem("test-theme")).toBe("dark");
  });

  it("reads theme from localStorage on mount", () => {
    localStorage.setItem("test-theme-2", "dark");
    render(
      <ThemeProvider storageKey="test-theme-2">
        <ThemeConsumer />
      </ThemeProvider>
    );
    expect(screen.getByTestId("theme").textContent).toBe("dark");
  });

  it("adds resolved theme class to document.documentElement", () => {
    render(
      <ThemeProvider defaultTheme="dark">
        <ThemeConsumer />
      </ThemeProvider>
    );
    expect(document.documentElement.classList.contains("dark")).toBe(true);
  });

  it("removes old theme class when switching", async () => {
    const user = userEvent.setup();
    render(
      <ThemeProvider defaultTheme="dark">
        <ThemeConsumer />
      </ThemeProvider>
    );

    expect(document.documentElement.classList.contains("dark")).toBe(true);
    await user.click(screen.getByTestId("set-light"));
    expect(document.documentElement.classList.contains("light")).toBe(true);
    expect(document.documentElement.classList.contains("dark")).toBe(false);
  });

  it("renders children", () => {
    render(
      <ThemeProvider>
        <div data-testid="child">Hello</div>
      </ThemeProvider>
    );
    expect(screen.getByTestId("child")).toBeInTheDocument();
  });
});
