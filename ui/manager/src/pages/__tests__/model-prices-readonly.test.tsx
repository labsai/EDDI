import { describe, it, expect, beforeEach, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderPage } from "@eddi/app-kit/test/test-utils";
import { resetModelPriceMock } from "@eddi/app-kit/test/mocks/model-prices-handlers";

vi.mock("@eddi/app-kit/hooks/use-auth", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@eddi/app-kit/hooks/use-auth")>()),
  useIsAdminOrUnknown: () => false,
}));

import { ModelPricesPage } from "../model-prices";

describe("ModelPricesPage without the admin role", () => {
  beforeEach(() => resetModelPriceMock());

  it("offers no write control (the server answers every write with 403)", async () => {
    renderPage("/manage/model-prices", <ModelPricesPage />);
    expect(await screen.findByTestId("model-prices-save")).toBeDisabled();
    expect(screen.getByTestId("model-prices-add")).toBeDisabled();
    expect(screen.getByTestId("model-prices-import")).toBeDisabled();
    expect(screen.getByTestId("model-prices-load-reference")).toBeDisabled();
    expect(screen.getByTestId("model-prices-policy-select")).toBeDisabled();
  });
});
