import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { OutputEditor, type OutputConfig } from "@/components/editors/output-editor";

const emptyConfig: OutputConfig = {
  lang: "",
  outputSet: [],
};

const populatedConfig: OutputConfig = {
  lang: "en",
  outputSet: [
    {
      action: "greet",
      timesOccurred: 0,
      outputs: [
        {
          valueAlternatives: [
            { type: "text", text: "Hello!" },
            { type: "text", text: "Hi there!" },
          ],
        },
      ],
      quickReplies: [
        { value: "Yes", expressions: "yes,yep", isDefault: true },
      ],
    },
  ],
};

describe("OutputEditor", () => {
  const onChange = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders with data-testid output-editor", () => {
    renderWithProviders(
      <OutputEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("output-editor")).toBeInTheDocument();
  });

  it("shows language input", () => {
    renderWithProviders(
      <OutputEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByText("Language")).toBeInTheDocument();
    expect(
      screen.getByPlaceholderText("e.g. en, de")
    ).toBeInTheDocument();
  });

  it("shows no output sets message when empty", () => {
    renderWithProviders(
      <OutputEditor data={emptyConfig} onChange={onChange} />
    );
    expect(
      screen.getByText("No output sets configured")
    ).toBeInTheDocument();
  });

  it("shows add output set button", () => {
    renderWithProviders(
      <OutputEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("add-output-btn")).toBeInTheDocument();
    expect(screen.getByText("Add Output Set")).toBeInTheDocument();
  });

  it("hides add output set button in readOnly mode", () => {
    renderWithProviders(
      <OutputEditor data={emptyConfig} onChange={onChange} readOnly />
    );
    expect(screen.queryByTestId("add-output-btn")).not.toBeInTheDocument();
  });

  it("calls onChange when add output set is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <OutputEditor data={emptyConfig} onChange={onChange} />
    );
    await user.click(screen.getByTestId("add-output-btn"));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        outputSet: [
          expect.objectContaining({
            action: "",
            timesOccurred: 0,
          }),
        ],
      })
    );
  });

  it("renders populated config with output sets", () => {
    renderWithProviders(
      <OutputEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("output-config-editor")).toBeInTheDocument();
    expect(screen.getByDisplayValue("greet")).toBeInTheDocument();
  });

  it("shows output items", () => {
    renderWithProviders(
      <OutputEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByDisplayValue("Hello!")).toBeInTheDocument();
    expect(screen.getByDisplayValue("Hi there!")).toBeInTheDocument();
  });

  it("shows quick replies", () => {
    renderWithProviders(
      <OutputEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByDisplayValue("Yes")).toBeInTheDocument();
    expect(screen.getByDisplayValue("yes,yep")).toBeInTheDocument();
  });

  it("shows quick replies section", () => {
    renderWithProviders(
      <OutputEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByText("Quick Replies")).toBeInTheDocument();
  });

  it("shows alternative group heading", () => {
    renderWithProviders(
      <OutputEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByText(/Alternative Group/)).toBeInTheDocument();
  });

  it("shows output sets heading", () => {
    renderWithProviders(
      <OutputEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByText("Output Sets")).toBeInTheDocument();
  });

  it("shows language value for populated config", () => {
    renderWithProviders(
      <OutputEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByDisplayValue("en")).toBeInTheDocument();
  });
});
