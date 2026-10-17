import { describe, it, expect } from "vitest";
import { useState } from "react";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { CronField } from "@/components/editors/ingestion-schedule";

/** A CronField whose value can also be changed from outside it, as Discard does. */
function Harness({ initial }: { initial?: string }) {
  const [cron, setCron] = useState<string | undefined>(initial);
  return (
    <>
      <CronField value={cron} onChange={setCron} testId="f" />
      <button type="button" onClick={() => setCron("0 2 * * *")} data-testid="external-preset">
        preset
      </button>
      <button type="button" onClick={() => setCron("*/30 * * * *")} data-testid="external-custom">
        custom
      </button>
    </>
  );
}

describe("CronField", () => {
  it("follows a value changed from outside: a preset closes the custom input", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial="*/30 * * * *" />);
    expect(screen.getByTestId("f-cron")).toHaveValue("*/30 * * * *");

    await user.click(screen.getByTestId("external-preset"));

    expect(screen.queryByTestId("f-cron")).not.toBeInTheDocument();
    expect(screen.getByTestId("f-cron-preset")).toHaveValue("0 2 * * *");
  });

  it("follows a value changed from outside: a custom cron opens the custom input", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial="0 2 * * *" />);
    expect(screen.queryByTestId("f-cron")).not.toBeInTheDocument();

    await user.click(screen.getByTestId("external-custom"));

    expect(screen.getByTestId("f-cron")).toHaveValue("*/30 * * * *");
  });

  it("keeps the custom input open while typing a cron that happens to match a preset", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial="*/30 * * * *" />);
    const input = screen.getByTestId("f-cron");

    await user.clear(input);
    await user.type(input, "0 2 * * *");

    expect(screen.getByTestId("f-cron")).toHaveValue("0 2 * * *");
  });
});
