import { describe, it, expect, vi } from "vitest";
import { screen, within } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ProviderRegionSelect, ProviderSelect } from "../provider-select";

describe("ProviderSelect", () => {
  it("groups the providers and labels them by display name", () => {
    renderWithProviders(<ProviderSelect value="xai" onChange={vi.fn()} testId="ps" />);
    const select = screen.getByTestId("ps") as HTMLSelectElement;
    expect(select.selectedOptions[0]?.text).toBe("xAI Grok");
    expect(select.querySelectorAll("optgroup")).toHaveLength(4);
  });

  it("shows a placeholder for an empty value instead of silently showing the first provider", () => {
    renderWithProviders(<ProviderSelect value="" onChange={vi.fn()} testId="ps" />);
    const select = screen.getByTestId("ps") as HTMLSelectElement;
    expect(select.value).toBe("");
    expect(select.selectedOptions[0]?.text).toBe("Select a provider");
  });

  it("renders leadingOptions ahead of the groups and skips the placeholder", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <ProviderSelect
        value=""
        onChange={onChange}
        testId="ps"
        leadingOptions={<option value="">None</option>}
      />,
    );
    const select = screen.getByTestId("ps") as HTMLSelectElement;
    expect(select.selectedOptions[0]?.text).toBe("None");
    expect(within(select).queryByText("Select a provider")).not.toBeInTheDocument();
    await user.selectOptions(select, "groq");
    expect(onChange).toHaveBeenCalledWith("groq");
  });

  it("names the control through ariaLabel", () => {
    renderWithProviders(<ProviderSelect value="openai" onChange={vi.fn()} ariaLabel="Model type" />);
    expect(screen.getByRole("combobox", { name: "Model type" })).toBeInTheDocument();
  });

  it("keeps an unknown value as a custom option", () => {
    renderWithProviders(<ProviderSelect value="acme" onChange={vi.fn()} testId="ps" />);
    expect((screen.getByTestId("ps") as HTMLSelectElement).selectedOptions[0]?.text).toContain("acme");
  });
});

describe("ProviderRegionSelect", () => {
  it("renders nothing for a single-endpoint provider", () => {
    const { container } = renderWithProviders(
      <ProviderRegionSelect provider="deepseek" baseUrl="" onBaseUrlChange={vi.fn()} />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it("merges a className over the defaults and can drop its own label", () => {
    renderWithProviders(
      <ProviderRegionSelect
        provider="qwen"
        baseUrl=""
        onBaseUrlChange={vi.fn()}
        hideLabel
        className="h-10 rounded-md"
        testId="r"
      />,
    );
    const select = screen.getByTestId("r");
    expect(select).toHaveClass("h-10", "rounded-md");
    expect(select).not.toHaveClass("rounded-lg");
    expect(screen.queryByText("Region")).not.toBeInTheDocument();
  });

  it("reports the region URL, or an empty string for the default region", async () => {
    const onBaseUrlChange = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <ProviderRegionSelect provider="qwen" baseUrl="" onBaseUrlChange={onBaseUrlChange} testId="r" />,
    );
    await user.selectOptions(screen.getByTestId("r"), "cn");
    expect(onBaseUrlChange).toHaveBeenLastCalledWith("https://dashscope.aliyuncs.com/compatible-mode/v1");
  });

  it("marks a user-typed URL as a disabled custom option", () => {
    renderWithProviders(
      <ProviderRegionSelect provider="qwen" baseUrl="https://gw.example/v1" onBaseUrlChange={vi.fn()} testId="r" />,
    );
    const select = screen.getByTestId("r");
    expect(select).toHaveValue("custom");
    expect(within(select).getByRole("option", { name: "Custom URL (set below)" })).toBeDisabled();
  });
});
