import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { VersionPicker } from "@/components/editors/version-picker";

describe("VersionPicker", () => {
  it("renders version badge when only 1 version", () => {
    renderWithProviders(
      <VersionPicker
        versions={[{ version: 1 }]}
        current={1}
        onChange={vi.fn()}
      />
    );
    expect(screen.getByTestId("version-badge")).toBeInTheDocument();
    expect(screen.getByText("v1")).toBeInTheDocument();
  });

  it("renders badge for empty version list", () => {
    renderWithProviders(
      <VersionPicker
        versions={[]}
        current={1}
        onChange={vi.fn()}
      />
    );
    expect(screen.getByTestId("version-badge")).toBeInTheDocument();
  });

  it("renders dropdown when multiple versions", () => {
    renderWithProviders(
      <VersionPicker
        versions={[{ version: 2 }, { version: 1 }]}
        current={2}
        onChange={vi.fn()}
      />
    );
    expect(screen.getByTestId("version-picker")).toBeInTheDocument();
    const select = screen.getByRole("combobox");
    expect(select).toBeInTheDocument();
  });

  it("shows all version options", () => {
    renderWithProviders(
      <VersionPicker
        versions={[{ version: 3 }, { version: 2 }, { version: 1 }]}
        current={3}
        onChange={vi.fn()}
      />
    );
    expect(screen.getByText(/v3/)).toBeInTheDocument();
    expect(screen.getByText(/v2/)).toBeInTheDocument();
    expect(screen.getByText(/v1/)).toBeInTheDocument();
  });

  it("calls onChange when version is selected", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <VersionPicker
        versions={[{ version: 2 }, { version: 1 }]}
        current={2}
        onChange={onChange}
      />
    );
    const select = screen.getByTestId("version-picker");
    await user.selectOptions(select, "1");
    expect(onChange).toHaveBeenCalledWith(1);
  });

  it("shows relative time when lastModifiedOn is provided", () => {
    renderWithProviders(
      <VersionPicker
        versions={[
          { version: 2, lastModifiedOn: Date.now() - 60000 },
          { version: 1, lastModifiedOn: Date.now() - 3600000 },
        ]}
        current={2}
        onChange={vi.fn()}
      />
    );
    expect(screen.getByText(/1m ago/)).toBeInTheDocument();
    expect(screen.getByText(/1h ago/)).toBeInTheDocument();
  });

  it("disables dropdown when disabled is true", () => {
    renderWithProviders(
      <VersionPicker
        versions={[{ version: 2 }, { version: 1 }]}
        current={2}
        onChange={vi.fn()}
        disabled
      />
    );
    expect(screen.getByTestId("version-picker")).toBeDisabled();
  });

  it("has accessible label", () => {
    renderWithProviders(
      <VersionPicker
        versions={[{ version: 2 }, { version: 1 }]}
        current={2}
        onChange={vi.fn()}
      />
    );
    expect(
      screen.getByLabelText("Select version")
    ).toBeInTheDocument();
  });
});
