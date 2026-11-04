import { act, render, screen } from "@testing-library/react";
import { toast } from "sonner";
import { describe, expect, it } from "vitest";
import { cn } from "../lib/cn";
import { ThemeProvider } from "../theme/theme-provider";
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
