import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import {
  DictionaryEditor,
  type DictionaryConfig,
} from "@/components/editors/dictionary-editor";

const emptyConfig: DictionaryConfig = {
  lang: "",
  words: [],
  regExs: [],
  phrases: [],
};

const populatedConfig: DictionaryConfig = {
  lang: "en",
  words: [
    { word: "hello", expressions: "greeting(hello)", frequency: 1 },
  ],
  phrases: [
    { phrase: "good morning", expressions: "greeting(morning)" },
  ],
  regExs: [
    { regEx: "\\d+", expressions: "number(*)" },
  ],
};

describe("DictionaryEditor", () => {
  const onChange = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders with data-testid dictionary-editor", () => {
    renderWithProviders(
      <DictionaryEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("dictionary-editor")).toBeInTheDocument();
  });

  it("shows language input", () => {
    renderWithProviders(
      <DictionaryEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByText("Language")).toBeInTheDocument();
    expect(
      screen.getByPlaceholderText("e.g. en, de")
    ).toBeInTheDocument();
  });

  it("shows Words section", () => {
    renderWithProviders(
      <DictionaryEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByText("Words")).toBeInTheDocument();
  });

  it("shows Phrases section", () => {
    renderWithProviders(
      <DictionaryEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByText("Phrases")).toBeInTheDocument();
  });

  it("shows Regular Expressions section", () => {
    renderWithProviders(
      <DictionaryEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByText("Regular Expressions")).toBeInTheDocument();
  });

  it("shows None defined for empty sections", () => {
    renderWithProviders(
      <DictionaryEditor data={emptyConfig} onChange={onChange} />
    );
    const nones = screen.getAllByText("None defined");
    expect(nones.length).toBe(3); // words, phrases, regex
  });

  it("renders word rows when populated", () => {
    renderWithProviders(
      <DictionaryEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("word-row")).toBeInTheDocument();
    expect(screen.getByDisplayValue("hello")).toBeInTheDocument();
    expect(screen.getByDisplayValue("greeting(hello)")).toBeInTheDocument();
  });

  it("renders phrase rows when populated", () => {
    renderWithProviders(
      <DictionaryEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("phrase-row")).toBeInTheDocument();
    expect(screen.getByDisplayValue("good morning")).toBeInTheDocument();
  });

  it("renders regex rows when populated", () => {
    renderWithProviders(
      <DictionaryEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("regex-row")).toBeInTheDocument();
    expect(screen.getByDisplayValue("\\d+")).toBeInTheDocument();
  });

  it("calls onChange when Add Word is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <DictionaryEditor data={emptyConfig} onChange={onChange} />
    );
    await user.click(screen.getByText("Add Word"));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        words: [{ word: "", expressions: "", frequency: 0 }],
      })
    );
  });

  it("calls onChange when Add Phrase is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <DictionaryEditor data={emptyConfig} onChange={onChange} />
    );
    await user.click(screen.getByText("Add Phrase"));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        phrases: [{ phrase: "", expressions: "" }],
      })
    );
  });

  it("calls onChange when Add RegEx is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <DictionaryEditor data={emptyConfig} onChange={onChange} />
    );
    await user.click(screen.getByText("Add RegEx"));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        regExs: [{ regEx: "", expressions: "" }],
      })
    );
  });

  it("hides add buttons in readOnly mode", () => {
    renderWithProviders(
      <DictionaryEditor data={emptyConfig} onChange={onChange} readOnly />
    );
    expect(screen.queryByText("Add Word")).not.toBeInTheDocument();
    expect(screen.queryByText("Add Phrase")).not.toBeInTheDocument();
    expect(screen.queryByText("Add RegEx")).not.toBeInTheDocument();
  });

  it("shows counts for each section", () => {
    renderWithProviders(
      <DictionaryEditor data={populatedConfig} onChange={onChange} />
    );
    // Each section should show its count
    const ones = screen.getAllByText("1");
    expect(ones.length).toBeGreaterThanOrEqual(3);
  });

  it("handles null data gracefully", () => {
    renderWithProviders(
      <DictionaryEditor
        data={null as unknown as DictionaryConfig}
        onChange={onChange}
      />
    );
    expect(screen.getByTestId("dictionary-editor")).toBeInTheDocument();
  });
});
