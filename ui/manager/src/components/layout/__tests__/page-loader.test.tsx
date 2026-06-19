import { describe, it, expect } from "vitest";
import { renderWithProviders } from "@/test/test-utils";
import { PageLoader } from "@/components/layout/page-loader";

describe("PageLoader", () => {
  it("renders skeleton placeholders", () => {
    renderWithProviders(<PageLoader />);
    // The component renders multiple Skeleton elements
    // Skeleton renders as <div> with specific classes
    const skeletons = document.querySelectorAll(
      ".animate-pulse"
    );
    expect(skeletons.length).toBeGreaterThanOrEqual(5);
  });

  it("renders without crashing", () => {
    const { container } = renderWithProviders(<PageLoader />);
    expect(container.firstChild).toBeInTheDocument();
  });
});
