import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, fireEvent } from "@testing-library/react";
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

  // ── Regression: image/applicationLink must use the backend field names ─────
  // ImageOutputItem -> uri/alt ; ApplicationLinkOutputItem -> path/label/delay.
  // The editor previously read/wrote `url` for both, so Jackson ignored the
  // value (silent data loss) and existing uri/path values rendered blank.
  const mediaConfig: OutputConfig = {
    lang: "en",
    outputSet: [
      {
        action: "show",
        timesOccurred: 0,
        outputs: [
          {
            valueAlternatives: [
              { type: "image", uri: "https://x/p.png", alt: "a picture" },
              {
                type: "applicationLink",
                path: "/open",
                label: "Open",
                delay: 500,
              },
            ],
          },
        ],
        quickReplies: [],
      },
    ],
  };

  it("renders image uri and alt from the backend fields", () => {
    renderWithProviders(
      <OutputEditor data={mediaConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("output-image-uri")).toHaveValue(
      "https://x/p.png"
    );
    expect(screen.getByTestId("output-image-alt")).toHaveValue("a picture");
  });

  it("renders applicationLink path, label and delay", () => {
    renderWithProviders(
      <OutputEditor data={mediaConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("output-link-path")).toHaveValue("/open");
    expect(screen.getByTestId("output-link-label")).toHaveValue("Open");
    expect(screen.getByTestId("output-link-delay")).toHaveValue(500);
  });

  it("writes an image URL to 'uri', never the ignored 'url' field", async () => {
    const cfg: OutputConfig = {
      lang: "",
      outputSet: [
        {
          action: "a",
          timesOccurred: 0,
          outputs: [{ valueAlternatives: [{ type: "image" }] }],
          quickReplies: [],
        },
      ],
    };
    const user = userEvent.setup();
    renderWithProviders(<OutputEditor data={cfg} onChange={onChange} />);
    await user.type(screen.getByTestId("output-image-uri"), "x");
    const arg = onChange.mock.lastCall![0] as OutputConfig;
    const item = arg.outputSet[0]!.outputs[0]!.valueAlternatives[0]!;
    expect(item.uri).toBe("x");
    expect(item).not.toHaveProperty("url");
  });

  it("writes an applicationLink URL to 'path', never 'url'", async () => {
    const cfg: OutputConfig = {
      lang: "",
      outputSet: [
        {
          action: "a",
          timesOccurred: 0,
          outputs: [{ valueAlternatives: [{ type: "applicationLink" }] }],
          quickReplies: [],
        },
      ],
    };
    const user = userEvent.setup();
    renderWithProviders(<OutputEditor data={cfg} onChange={onChange} />);
    await user.type(screen.getByTestId("output-link-path"), "y");
    const arg = onChange.mock.lastCall![0] as OutputConfig;
    const item = arg.outputSet[0]!.outputs[0]!.valueAlternatives[0]!;
    expect(item.path).toBe("y");
    expect(item).not.toHaveProperty("url");
  });

  // ─── button (ButtonOutputItem: buttonType, label, onPress) ─────────────────

  const buttonConfig: OutputConfig = {
    lang: "en",
    outputSet: [
      {
        action: "menu",
        timesOccurred: 0,
        outputs: [
          {
            valueAlternatives: [
              {
                type: "button",
                buttonType: "postback",
                label: "Continue",
                onPress: { action: "next" },
              },
            ],
          },
        ],
        quickReplies: [],
      },
    ],
  };

  it("renders per-field button form with backend field names", () => {
    renderWithProviders(<OutputEditor data={buttonConfig} onChange={onChange} />);
    expect(screen.getByTestId("output-button-fields")).toBeInTheDocument();
    expect(screen.getByTestId("output-button-type")).toHaveValue("postback");
    expect(screen.getByTestId("output-button-label")).toHaveValue("Continue");
    expect(screen.getByTestId("output-button-onpress")).toHaveValue(
      '{"action":"next"}'
    );
  });

  it("writes the button 'buttonType' field on edit", async () => {
    const cfg: OutputConfig = {
      lang: "",
      outputSet: [
        {
          action: "a",
          timesOccurred: 0,
          outputs: [{ valueAlternatives: [{ type: "button" }] }],
          quickReplies: [],
        },
      ],
    };
    const user = userEvent.setup();
    renderWithProviders(<OutputEditor data={cfg} onChange={onChange} />);
    await user.type(screen.getByTestId("output-button-type"), "x");
    const item = (onChange.mock.lastCall![0] as OutputConfig).outputSet[0]!
      .outputs[0]!.valueAlternatives[0]!;
    expect(item.buttonType).toBe("x");
    expect(item.type).toBe("button");
  });

  it("parses the button 'onPress' JSON into an object", () => {
    const cfg: OutputConfig = {
      lang: "",
      outputSet: [
        {
          action: "a",
          timesOccurred: 0,
          outputs: [{ valueAlternatives: [{ type: "button" }] }],
          quickReplies: [],
        },
      ],
    };
    renderWithProviders(<OutputEditor data={cfg} onChange={onChange} />);
    fireEvent.change(screen.getByTestId("output-button-onpress"), {
      target: { value: '{"action":"buy","id":3}' },
    });
    const item = (onChange.mock.lastCall![0] as OutputConfig).outputSet[0]!
      .outputs[0]!.valueAlternatives[0]!;
    expect(item.onPress).toEqual({ action: "buy", id: 3 });
  });

  // ─── inputField (InputFieldOutputItem) ─────────────────────────────────────

  const inputFieldConfig: OutputConfig = {
    lang: "en",
    outputSet: [
      {
        action: "ask",
        timesOccurred: 0,
        outputs: [
          {
            valueAlternatives: [
              {
                type: "inputField",
                subType: "email",
                placeholder: "you@example.com",
                label: "Email",
                defaultValue: "",
                validation: {
                  minLength: 3,
                  maxLength: 50,
                  validationErrorMessage: "Invalid",
                },
              },
            ],
          },
        ],
        quickReplies: [],
      },
    ],
  };

  it("renders per-field inputField form with backend field names", () => {
    renderWithProviders(
      <OutputEditor data={inputFieldConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("output-inputfield-fields")).toBeInTheDocument();
    expect(screen.getByTestId("output-input-subtype")).toHaveValue("email");
    expect(screen.getByTestId("output-input-placeholder")).toHaveValue(
      "you@example.com"
    );
    expect(screen.getByTestId("output-input-label")).toHaveValue("Email");
    expect(screen.getByTestId("output-input-minlength")).toHaveValue(3);
    expect(screen.getByTestId("output-input-maxlength")).toHaveValue(50);
    expect(screen.getByTestId("output-input-validationmsg")).toHaveValue(
      "Invalid"
    );
  });

  it("writes the inputField 'subType' field on edit", async () => {
    const cfg: OutputConfig = {
      lang: "",
      outputSet: [
        {
          action: "a",
          timesOccurred: 0,
          outputs: [{ valueAlternatives: [{ type: "inputField" }] }],
          quickReplies: [],
        },
      ],
    };
    const user = userEvent.setup();
    renderWithProviders(<OutputEditor data={cfg} onChange={onChange} />);
    await user.type(screen.getByTestId("output-input-subtype"), "z");
    const item = (onChange.mock.lastCall![0] as OutputConfig).outputSet[0]!
      .outputs[0]!.valueAlternatives[0]!;
    expect(item.subType).toBe("z");
  });

  it("writes nested inputField 'validation.minLength' on edit", () => {
    const cfg: OutputConfig = {
      lang: "",
      outputSet: [
        {
          action: "a",
          timesOccurred: 0,
          outputs: [{ valueAlternatives: [{ type: "inputField" }] }],
          quickReplies: [],
        },
      ],
    };
    renderWithProviders(<OutputEditor data={cfg} onChange={onChange} />);
    fireEvent.change(screen.getByTestId("output-input-minlength"), {
      target: { value: "5" },
    });
    const item = (onChange.mock.lastCall![0] as OutputConfig).outputSet[0]!
      .outputs[0]!.valueAlternatives[0]!;
    expect(item.validation).toEqual({ minLength: 5 });
  });

  // ─── generic fallback is editable JSON (not read-only) ─────────────────────

  it("renders an editable JSON fallback for types without a dedicated form", () => {
    const cfg: OutputConfig = {
      lang: "",
      outputSet: [
        {
          action: "a",
          timesOccurred: 0,
          outputs: [
            { valueAlternatives: [{ type: "agentFace", value: "smile" }] },
          ],
          quickReplies: [],
        },
      ],
    };
    renderWithProviders(<OutputEditor data={cfg} onChange={onChange} />);
    const input = screen.getByTestId("output-json-fallback");
    expect(input).not.toHaveAttribute("readonly");
    fireEvent.change(input, { target: { value: '{"value":"wave"}' } });
    const item = (onChange.mock.lastCall![0] as OutputConfig).outputSet[0]!
      .outputs[0]!.valueAlternatives[0]!;
    expect(item.type).toBe("agentFace");
    expect(item.value).toBe("wave");
  });
});
