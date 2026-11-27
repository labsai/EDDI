import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { Sparkline } from "../sparkline";

const pt = (day: string, value: number | null) => ({ day, value, n: value === null ? 0 : 10 });

describe("Sparkline", () => {
  it("draws days that stand alone between gaps as dots", () => {
    render(<Sparkline points={[pt("2026-11-01", 1), pt("2026-11-02", null), pt("2026-11-03", 3)]} unit="count" label="Sessions" />);
    const dots = screen.getByTestId("health-sparkline-dots");
    // One zero-length, round-capped subpath per isolated day.
    expect(dots.getAttribute("d")?.match(/M/g)).toHaveLength(2);
    expect(dots).toHaveAttribute("stroke-linecap", "round");
  });

  it("draws no dots for a continuous series", () => {
    render(<Sparkline points={[pt("2026-11-01", 1), pt("2026-11-02", 2)]} unit="count" label="Sessions" />);
    expect(screen.getByTestId("health-sparkline")).toBeInTheDocument();
    expect(screen.queryByTestId("health-sparkline-dots")).toBeNull();
  });
});
