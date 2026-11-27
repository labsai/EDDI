import { describe, expect, it } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@eddi/app-kit/test/test-utils";
import type { Band, HealthStatus } from "@eddi/app-kit/lib/api/health";
import { BandBadge, StatusBadge } from "../band-badge";
import { BAND_META, STATUS_META } from "../format";

// The badges take their colours from the shared Badge variants; `classes` is the
// same colours for the pills that are not a Badge (the status filter). Both must agree.
const tokens = (classes: string) => classes.split(/\s+/).filter(Boolean);

describe("BandBadge", () => {
  it.each(Object.keys(BAND_META) as Band[])("draws %s with the band's shared Badge variant", (band) => {
    renderWithProviders(<BandBadge band={band} />);
    const badge = screen.getByTestId("health-band");
    expect(badge).toHaveAttribute("data-band", band);
    expect(badge).toHaveClass(...tokens(BAND_META[band].classes));
    expect(badge).toHaveClass("gap-1", "px-2", "font-medium");
    expect(badge).not.toHaveClass("text-foreground");
  });

  it("draws an unknown band as grey", () => {
    renderWithProviders(<BandBadge band="PURPLE" />);
    expect(screen.getByTestId("health-band")).toHaveClass(...tokens(BAND_META.GREY.classes));
  });
});

describe("StatusBadge", () => {
  it.each(Object.keys(STATUS_META) as HealthStatus[])("draws %s with the status's shared Badge variant", (status) => {
    renderWithProviders(<StatusBadge status={status} />);
    const badge = screen.getByTestId("health-status-badge");
    expect(badge).toHaveAttribute("data-status", status);
    expect(badge).toHaveClass(...tokens(STATUS_META[status].classes));
    expect(badge).toHaveClass("gap-1.5", "py-1");
    expect(badge).not.toHaveClass("text-foreground");
  });
});
