import { act, render, screen } from "@testing-library/react";
import { toast } from "sonner";
import { afterEach, describe, expect, it } from "vitest";
import { cn } from "../lib/cn";
import { ThemeProvider } from "../theme/theme-provider";
import { Badge } from "./badge";
import { Button, buttonVariants } from "./button";
import { ThemedToaster } from "./toaster";

describe("Button", () => {
  it("renders the primary, medium button by default", () => {
    render(<Button data-testid="b">Save</Button>);
    const button = screen.getByTestId("b");
    expect(button.tagName).toBe("BUTTON");
    expect(button.className).toContain("bg-primary");
    expect(button.className).toContain("h-10");
  });

  it("takes a variant and size, and a className that wins a conflict", () => {
    render(
      <Button data-testid="b" variant="ghost" size="iconSm" className="h-12">
        x
      </Button>,
    );
    const button = screen.getByTestId("b");
    expect(button.className).toContain("hover:bg-secondary");
    expect(button.className).toContain("h-12");
    expect(button.className).not.toContain("h-6 ");
  });

  it("renders its child instead with asChild", () => {
    render(
      <Button asChild>
        <a href="/x" data-testid="link">
          Go
        </a>
      </Button>,
    );
    expect(screen.getByTestId("link").className).toContain("inline-flex");
  });

  it("exposes the variants for links styled as buttons", () => {
    expect(buttonVariants({ variant: "outline" })).toContain("border-input");
  });
});

describe("Badge", () => {
  it.each([
    ["success-outline", ["border-emerald-500/40", "bg-emerald-500/10", "text-emerald-700", "dark:text-emerald-400"]],
    ["warning-outline", ["border-warning/40", "bg-warning/10", "text-warning"]],
    ["destructive-outline", ["border-destructive/40", "bg-destructive/10", "text-destructive"]],
    ["muted-outline", ["border-border", "bg-muted", "text-muted-foreground"]],
  ] as const)("draws the %s variant as a bordered, tinted pill", (variant, classes) => {
    render(
      <Badge data-testid="b" variant={variant}>
        x
      </Badge>,
    );
    const badge = screen.getByTestId("b");
    for (const c of classes) expect(badge).toHaveClass(c);
    expect(badge).toHaveClass("rounded-full", "border");
    expect(badge).not.toHaveClass("border-transparent");
  });

  it("keeps layout classes from the call site", () => {
    render(
      <Badge data-testid="b" variant="success-outline" className="gap-1 px-2">
        x
      </Badge>,
    );
    const badge = screen.getByTestId("b");
    expect(badge).toHaveClass("gap-1", "px-2");
    expect(badge).not.toHaveClass("px-2.5");
  });
});

describe("cn", () => {
  it("lets the later Tailwind utility win", () => {
    const hidden = false;
    expect(cn("px-2 text-sm", hidden && "hidden", "px-4")).toBe("text-sm px-4");
  });
});

describe("ThemedToaster", () => {
  async function toasterFor(rtl: boolean) {
    render(
      <ThemeProvider defaultTheme="dark">
        <ThemedToaster rtl={rtl} />
      </ThemeProvider>,
    );
    act(() => {
      toast("Saved");
    });
    await screen.findByText("Saved");
    return document.querySelector("[data-sonner-toaster]")!;
  }

  // Sonner keeps its toasts in a module-level store that outlives the
  // unmounted Toaster, so without this the next test finds two "Saved".
  afterEach(() => {
    act(() => {
      toast.dismiss();
    });
  });

  it("sits bottom-right, left to right, in the resolved theme", async () => {
    const toaster = await toasterFor(false);
    expect(toaster.getAttribute("data-x-position")).toBe("right");
    expect(toaster.getAttribute("dir")).toBe("ltr");
    expect(toaster.getAttribute("data-sonner-theme")).toBe("dark");
  });

  it("moves to the trailing corner, bottom-left, for a right-to-left page", async () => {
    const toaster = await toasterFor(true);
    expect(toaster.getAttribute("data-x-position")).toBe("left");
    expect(toaster.getAttribute("dir")).toBe("rtl");
  });
});
