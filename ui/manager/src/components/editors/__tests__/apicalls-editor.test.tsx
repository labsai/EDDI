import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, within } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import {
  ApiCallsEditor,
  PropertyInstructionsEditor,
  OutputBuildInstructionsEditor,
  QrBuildInstructionsEditor,
  type HttpCallsConfig,
  type PropertyInstruction,
  type OutputBuildingInstruction,
  type QuickRepliesBuildingInstruction,
} from "@/components/editors/apicalls-editor";

// Mock monaco
vi.mock("@monaco-editor/react", () => ({
  default: vi.fn(({ value }: { value: string }) => (
    <textarea data-testid="mock-monaco" defaultValue={value} />
  )),
}));

// Mock discoverEndpoints to avoid real network calls
const mockDiscoverEndpoints = vi.fn();
vi.mock("@/lib/api/openapi-discover", () => ({
  discoverEndpoints: (...args: unknown[]) => mockDiscoverEndpoints(...args),
}));

const emptyConfig: HttpCallsConfig = {
  targetServerUrl: "",
  httpCalls: [],
};

const populatedConfig: HttpCallsConfig = {
  targetServerUrl: "https://api.example.com",
  httpCalls: [
    {
      name: "getWeather",
      description: "Fetches weather data",
      actions: ["get_weather"],
      saveResponse: false,
      fireAndForget: false,
      isBatchCalls: false,
      request: {
        path: "/weather",
        method: "GET",
        headers: { "Content-Type": "application/json" },
        queryParams: { city: "Vienna" },
        contentType: "application/json",
        body: "",
      },
      preRequest: {
        propertyInstructions: [
          { name: "apiKey", valueString: "secret", scope: "conversation", override: true },
        ],
        delayBeforeExecutingInMillis: 500,
      },
      postResponse: {
        propertyInstructions: [
          { name: "result", valueString: "{response.data}", scope: "step", override: true },
        ],
        outputBuildInstructions: [
          {
            pathToTargetArray: "aiOutput.outputs",
            iterationObjectName: "obj",
            outputType: "text",
            outputValue: "{obj.text}",
          },
        ],
        qrBuildInstructions: [
          {
            pathToTargetArray: "aiOutput.quickReplies",
            iterationObjectName: "obj",
            quickReplyValue: "{obj.value}",
            quickReplyExpressions: "{obj.expressions}",
          },
        ],
      },
    },
  ],
};

const multiCallConfig: HttpCallsConfig = {
  targetServerUrl: "https://api.example.com",
  httpCalls: [
    {
      name: "firstCall",
      actions: ["action1"],
      request: {
        path: "/first",
        method: "GET",
        headers: {},
        queryParams: {},
        contentType: "application/json",
        body: "",
      },
    },
    {
      name: "secondCall",
      actions: ["action2"],
      request: {
        path: "/second",
        method: "POST",
        headers: {},
        queryParams: {},
        contentType: "application/json",
        body: '{"key": "value"}',
      },
    },
  ],
};

describe("ApiCallsEditor", () => {
  const onChange = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders with data-testid apicalls-editor", () => {
    renderWithProviders(
      <ApiCallsEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("apicalls-editor")).toBeInTheDocument();
  });

  it("shows target server URL input", () => {
    renderWithProviders(
      <ApiCallsEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("server-url-input")).toBeInTheDocument();
    expect(screen.getByText("Target Server URL")).toBeInTheDocument();
  });

  it("shows no HTTP calls message when empty", () => {
    renderWithProviders(
      <ApiCallsEditor data={emptyConfig} onChange={onChange} />
    );
    expect(
      screen.getByText("No HTTP calls configured")
    ).toBeInTheDocument();
  });

  it("shows add call button", () => {
    renderWithProviders(
      <ApiCallsEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("add-call-btn")).toBeInTheDocument();
    expect(screen.getByText("Add HTTP Call")).toBeInTheDocument();
  });

  it("hides add call button in readOnly mode", () => {
    renderWithProviders(
      <ApiCallsEditor data={emptyConfig} onChange={onChange} readOnly />
    );
    expect(screen.queryByTestId("add-call-btn")).not.toBeInTheDocument();
  });

  it("calls onChange when add call is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ApiCallsEditor data={emptyConfig} onChange={onChange} />
    );
    await user.click(screen.getByTestId("add-call-btn"));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        httpCalls: [
          expect.objectContaining({
            name: "",
            actions: [],
            request: expect.objectContaining({ method: "GET" }),
          }),
        ],
      })
    );
  });

  it("shows HTTP Calls heading", () => {
    renderWithProviders(
      <ApiCallsEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByText("HTTP Calls")).toBeInTheDocument();
  });

  it("shows OpenAPI import section when not readOnly", () => {
    renderWithProviders(
      <ApiCallsEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByText("Import from OpenAPI Spec")).toBeInTheDocument();
    expect(screen.getByTestId("spec-url-input")).toBeInTheDocument();
    expect(screen.getByTestId("discover-endpoints-btn")).toBeInTheDocument();
  });

  it("hides OpenAPI import section in readOnly mode", () => {
    renderWithProviders(
      <ApiCallsEditor data={emptyConfig} onChange={onChange} readOnly />
    );
    expect(
      screen.queryByText("Import from OpenAPI Spec")
    ).not.toBeInTheDocument();
  });

  it("renders populated config with server URL", () => {
    renderWithProviders(
      <ApiCallsEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("server-url-input")).toHaveValue(
      "https://api.example.com"
    );
  });

  // ─── Server URL editing ─────────────────────────────────────────────

  it("updates server URL when typed", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ApiCallsEditor data={emptyConfig} onChange={onChange} />
    );
    const input = screen.getByTestId("server-url-input");
    await user.type(input, "x");
    // onChange is called per-character, each with a fresh config since component is uncontrolled
    expect(onChange).toHaveBeenCalled();
    const firstCall = onChange.mock.calls[0]![0];
    expect(firstCall).toHaveProperty("targetServerUrl");
  });

  // ─── HttpCallEditor rendering ───────────────────────────────────────

  it("renders call name input with existing value", () => {
    renderWithProviders(
      <ApiCallsEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("call-name-input")).toHaveValue("getWeather");
  });

  it("renders method select with GET selected", () => {
    renderWithProviders(
      <ApiCallsEditor data={populatedConfig} onChange={onChange} />
    );
    const select = screen.getByTestId("method-select") as HTMLSelectElement;
    expect(select.value).toBe("GET");
  });

  it("renders all HTTP method options", () => {
    renderWithProviders(
      <ApiCallsEditor data={populatedConfig} onChange={onChange} />
    );
    const select = screen.getByTestId("method-select");
    const options = within(select).getAllByRole("option");
    expect(options).toHaveLength(7);
    expect(options.map((o) => o.textContent)).toEqual(
      expect.arrayContaining(["GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"])
    );
  });

  it("calls onChange when method is changed", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ApiCallsEditor data={populatedConfig} onChange={onChange} />
    );
    const select = screen.getByTestId("method-select");
    await user.selectOptions(select, "POST");
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        httpCalls: [
          expect.objectContaining({
            request: expect.objectContaining({ method: "POST" }),
          }),
        ],
      })
    );
  });

  it("calls onChange when call name is changed", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ApiCallsEditor data={populatedConfig} onChange={onChange} />
    );
    const input = screen.getByTestId("call-name-input");
    await user.clear(input);
    await user.type(input, "newName");
    expect(onChange).toHaveBeenCalled();
  });

  it("shows description input", () => {
    renderWithProviders(
      <ApiCallsEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByDisplayValue("Fetches weather data")).toBeInTheDocument();
  });

  it("shows content type field", () => {
    renderWithProviders(
      <ApiCallsEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByText("Content Type")).toBeInTheDocument();
  });

  it("renders multiple call editors", () => {
    renderWithProviders(
      <ApiCallsEditor data={multiCallConfig} onChange={onChange} />
    );
    const editors = screen.getAllByTestId("httpcall-editor");
    expect(editors).toHaveLength(2);
  });

  it("shows remove call button for each call", () => {
    renderWithProviders(
      <ApiCallsEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByLabelText("Remove Call")).toBeInTheDocument();
  });

  it("calls onChange to remove a call when remove is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ApiCallsEditor data={populatedConfig} onChange={onChange} />
    );
    await user.click(screen.getByLabelText("Remove Call"));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ httpCalls: [] })
    );
  });

  it("does not show remove button in readOnly mode", () => {
    renderWithProviders(
      <ApiCallsEditor data={populatedConfig} onChange={onChange} readOnly />
    );
    expect(screen.queryByLabelText("Remove Call")).not.toBeInTheDocument();
  });

  // ─── Section headings ─────────────────────────────────────────────

  it("shows section headings in the call editor", () => {
    renderWithProviders(
      <ApiCallsEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByText("Trigger Actions")).toBeInTheDocument();
    expect(screen.getByText("Request")).toBeInTheDocument();
    expect(screen.getByText("Headers")).toBeInTheDocument();
    expect(screen.getByText("Query Parameters")).toBeInTheDocument();
    expect(screen.getByText("Request Body")).toBeInTheDocument();
  });

  // ─── Action Tags ──────────────────────────────────────────────────

  it("renders existing action tags", () => {
    renderWithProviders(
      <ApiCallsEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByText("get_weather")).toBeInTheDocument();
  });

  it("shows No actions when actions array is empty", () => {
    const config: HttpCallsConfig = {
      httpCalls: [
        {
          name: "test",
          actions: [],
          request: { path: "/", method: "GET", headers: {}, queryParams: {}, contentType: "", body: "" },
        },
      ],
    };
    renderWithProviders(<ApiCallsEditor data={config} onChange={onChange} />);
    expect(screen.getByText("No actions")).toBeInTheDocument();
  });

  // ─── Options section ──────────────────────────────────────────────

  it("renders Options section header", () => {
    renderWithProviders(
      <ApiCallsEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByText("Options")).toBeInTheDocument();
  });

  it("shows Save Response and Fire and Forget after expanding Options", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ApiCallsEditor data={populatedConfig} onChange={onChange} />
    );
    // Options section has defaultOpen={false}, click to open
    await user.click(screen.getByText("Options"));
    expect(screen.getByText("Save Response")).toBeInTheDocument();
    expect(screen.getByText("Fire and Forget")).toBeInTheDocument();
  });

  it("shows Batch Calls checkbox after expanding Options", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ApiCallsEditor data={populatedConfig} onChange={onChange} />
    );
    await user.click(screen.getByText("Options"));
    expect(screen.getByText("Batch Calls")).toBeInTheDocument();
  });

  it("shows saveResponse additional fields when saveResponse is enabled", async () => {
    const user = userEvent.setup();
    const config: HttpCallsConfig = {
      httpCalls: [
        {
          name: "test",
          actions: [],
          saveResponse: true,
          responseObjectName: "myResponse",
          responseHeaderObjectName: "myHeaders",
          request: { path: "/", method: "GET", headers: {}, queryParams: {}, contentType: "", body: "" },
        },
      ],
    };
    renderWithProviders(<ApiCallsEditor data={config} onChange={onChange} />);
    // Options section is collapsed by default - open it first
    await user.click(screen.getByText("Options"));
    expect(screen.getByDisplayValue("myResponse")).toBeInTheDocument();
    expect(screen.getByDisplayValue("myHeaders")).toBeInTheDocument();
  });

  it("shows iteration object name field when batch mode is on", async () => {
    const user = userEvent.setup();
    const config: HttpCallsConfig = {
      httpCalls: [
        {
          name: "test",
          actions: [],
          isBatchCalls: true,
          iterationObjectName: "batchItem",
          request: { path: "/", method: "GET", headers: {}, queryParams: {}, contentType: "", body: "" },
        },
      ],
    };
    renderWithProviders(<ApiCallsEditor data={config} onChange={onChange} />);
    // Options section is collapsed by default - open it first
    await user.click(screen.getByText("Options"));
    expect(screen.getByDisplayValue("batchItem")).toBeInTheDocument();
  });

  // ─── Pre-Request section ──────────────────────────────────────────

  it("renders Pre-Request section", () => {
    renderWithProviders(
      <ApiCallsEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByText("Pre-Request")).toBeInTheDocument();
  });

  it("shows delay input in pre-request", () => {
    renderWithProviders(
      <ApiCallsEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByText("Delay (ms)")).toBeInTheDocument();
    expect(screen.getByDisplayValue("500")).toBeInTheDocument();
  });

  // ─── Post-Response section ────────────────────────────────────────

  it("renders Post-Response section with sub-sections", () => {
    renderWithProviders(
      <ApiCallsEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByText("Post-Response")).toBeInTheDocument();
    // Check sub-headings exist
    expect(screen.getByText("Output Build Instructions")).toBeInTheDocument();
    expect(screen.getByText("Quick Reply Build Instructions")).toBeInTheDocument();
  });

  // ─── OpenAPI Discovery flow ───────────────────────────────────────

  it("discover button is disabled when URL is empty", () => {
    renderWithProviders(
      <ApiCallsEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("discover-endpoints-btn")).toBeDisabled();
  });

  it("discover button is disabled for invalid URLs", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ApiCallsEditor data={emptyConfig} onChange={onChange} />
    );
    await user.type(screen.getByTestId("spec-url-input"), "not-a-url");
    expect(screen.getByTestId("discover-endpoints-btn")).toBeDisabled();
  });

  it("enables discover button with valid URL", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ApiCallsEditor data={emptyConfig} onChange={onChange} />
    );
    await user.type(screen.getByTestId("spec-url-input"), "https://api.example.com/openapi.json");
    expect(screen.getByTestId("discover-endpoints-btn")).not.toBeDisabled();
  });

  it("shows discovered endpoints after successful discovery", async () => {
    const user = userEvent.setup();
    mockDiscoverEndpoints.mockResolvedValue({
      title: "Test API",
      baseUrl: "https://api.test.com",
      endpointCount: 2,
      groups: {
        pets: {
          targetServerUrl: "https://api.test.com",
          httpCalls: [
            {
              name: "listPets",
              description: "List all pets",
              actions: ["list_pets"],
              request: { path: "/pets", method: "GET", headers: {}, queryParams: {}, contentType: "application/json", body: "" },
            },
            {
              name: "createPet",
              description: "Create a pet",
              actions: ["create_pet"],
              request: { path: "/pets", method: "POST", headers: {}, queryParams: {}, contentType: "application/json", body: "" },
            },
          ],
        },
      },
    });

    renderWithProviders(
      <ApiCallsEditor data={emptyConfig} onChange={onChange} />
    );

    await user.type(screen.getByTestId("spec-url-input"), "https://api.test.com/openapi.json");
    await user.click(screen.getByTestId("discover-endpoints-btn"));

    expect(await screen.findByTestId("discovered-endpoints-panel")).toBeInTheDocument();
    expect(screen.getByText(/Test API/)).toBeInTheDocument();
    expect(screen.getByTestId("import-append-btn")).toBeInTheDocument();
    expect(screen.getByTestId("import-replace-btn")).toBeInTheDocument();
  });

  it("shows discovery error when discovery fails", async () => {
    const user = userEvent.setup();
    mockDiscoverEndpoints.mockRejectedValue(new Error("Network error"));

    renderWithProviders(
      <ApiCallsEditor data={emptyConfig} onChange={onChange} />
    );

    await user.type(screen.getByTestId("spec-url-input"), "https://api.bad.com/spec.json");
    await user.click(screen.getByTestId("discover-endpoints-btn"));

    expect(await screen.findByTestId("discovery-error")).toBeInTheDocument();
    expect(screen.getByText("Network error")).toBeInTheDocument();
    expect(screen.getByText("Retry")).toBeInTheDocument();
  });

  it("appends discovered endpoints when import-append is clicked", async () => {
    const user = userEvent.setup();
    mockDiscoverEndpoints.mockResolvedValue({
      title: "Test API",
      baseUrl: "https://api.test.com",
      endpointCount: 1,
      groups: {
        pets: {
          targetServerUrl: "https://api.test.com",
          httpCalls: [
            {
              name: "listPets",
              actions: ["list_pets"],
              request: { path: "/pets", method: "GET", headers: {}, queryParams: {}, contentType: "application/json", body: "" },
            },
          ],
        },
      },
    });

    renderWithProviders(
      <ApiCallsEditor data={emptyConfig} onChange={onChange} />
    );

    await user.type(screen.getByTestId("spec-url-input"), "https://api.test.com/openapi.json");
    await user.click(screen.getByTestId("discover-endpoints-btn"));

    await screen.findByTestId("import-append-btn");
    await user.click(screen.getByTestId("import-append-btn"));

    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        httpCalls: [expect.objectContaining({ name: "listPets" })],
        targetServerUrl: "https://api.test.com",
      })
    );
  });

  it("shows replace confirmation when replacing existing calls", async () => {
    const user = userEvent.setup();
    mockDiscoverEndpoints.mockResolvedValue({
      title: "Test API",
      baseUrl: "https://api.test.com",
      endpointCount: 1,
      groups: {
        pets: {
          targetServerUrl: "https://api.test.com",
          httpCalls: [
            {
              name: "listPets",
              actions: [],
              request: { path: "/pets", method: "GET", headers: {}, queryParams: {}, contentType: "", body: "" },
            },
          ],
        },
      },
    });

    renderWithProviders(
      <ApiCallsEditor data={populatedConfig} onChange={onChange} />
    );

    await user.type(screen.getByTestId("spec-url-input"), "https://api.test.com/openapi.json");
    await user.click(screen.getByTestId("discover-endpoints-btn"));

    await screen.findByTestId("import-replace-btn");
    await user.click(screen.getByTestId("import-replace-btn"));

    // Shows confirmation dialog since there are existing calls
    expect(await screen.findByTestId("replace-confirm")).toBeInTheDocument();
  });

  it("replaces calls after confirming replacement", async () => {
    const user = userEvent.setup();
    mockDiscoverEndpoints.mockResolvedValue({
      title: "Test API",
      baseUrl: "https://api.test.com",
      endpointCount: 1,
      groups: {
        pets: {
          targetServerUrl: "https://api.test.com",
          httpCalls: [
            {
              name: "replacedCall",
              actions: [],
              request: { path: "/replaced", method: "PUT", headers: {}, queryParams: {}, contentType: "", body: "" },
            },
          ],
        },
      },
    });

    renderWithProviders(
      <ApiCallsEditor data={populatedConfig} onChange={onChange} />
    );

    await user.type(screen.getByTestId("spec-url-input"), "https://api.test.com/openapi.json");
    await user.click(screen.getByTestId("discover-endpoints-btn"));

    await screen.findByTestId("import-replace-btn");
    await user.click(screen.getByTestId("import-replace-btn"));

    // Confirm replacement
    await screen.findByTestId("replace-confirm-yes");
    await user.click(screen.getByTestId("replace-confirm-yes"));

    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        httpCalls: [expect.objectContaining({ name: "replacedCall" })],
      })
    );
  });

  // ─── Headers / KvEditor ───────────────────────────────────────────

  it("renders existing headers in KvEditor", () => {
    renderWithProviders(
      <ApiCallsEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByDisplayValue("Content-Type")).toBeInTheDocument();
    // application/json appears in both the header value and content type fields
    const matches = screen.getAllByDisplayValue("application/json");
    expect(matches.length).toBeGreaterThanOrEqual(2);
  });

  it("shows Add Header button", () => {
    renderWithProviders(
      <ApiCallsEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByText("Add Header")).toBeInTheDocument();
  });

  it("renders query params in KvEditor", () => {
    renderWithProviders(
      <ApiCallsEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByDisplayValue("city")).toBeInTheDocument();
    expect(screen.getByDisplayValue("Vienna")).toBeInTheDocument();
  });
});

// ─── PropertyInstructionsEditor (standalone) ─────────────────────────

describe("PropertyInstructionsEditor", () => {
  const onChange = vi.fn();

  beforeEach(() => vi.clearAllMocks());

  it("shows empty message when no instructions", () => {
    renderWithProviders(
      <PropertyInstructionsEditor instructions={[]} onChange={onChange} />
    );
    expect(screen.getByText("No property instructions")).toBeInTheDocument();
  });

  it("shows add button", () => {
    renderWithProviders(
      <PropertyInstructionsEditor instructions={[]} onChange={onChange} />
    );
    expect(screen.getByText("Add Property Instruction")).toBeInTheDocument();
  });

  it("adds a property instruction when add is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <PropertyInstructionsEditor instructions={[]} onChange={onChange} />
    );
    await user.click(screen.getByText("Add Property Instruction"));
    expect(onChange).toHaveBeenCalledWith([
      expect.objectContaining({ name: "", valueString: "", scope: "conversation" }),
    ]);
  });

  it("renders existing instruction with name and value", () => {
    const instructions: PropertyInstruction[] = [
      { name: "testProp", valueString: "testValue", scope: "step", override: true },
    ];
    renderWithProviders(
      <PropertyInstructionsEditor instructions={instructions} onChange={onChange} />
    );
    expect(screen.getByDisplayValue("testProp")).toBeInTheDocument();
    expect(screen.getByDisplayValue("testValue")).toBeInTheDocument();
  });

  it("removes instruction when remove button is clicked", async () => {
    const user = userEvent.setup();
    const instructions: PropertyInstruction[] = [
      { name: "first", valueString: "a", scope: "step", override: true },
      { name: "second", valueString: "b", scope: "conversation", override: false },
    ];
    renderWithProviders(
      <PropertyInstructionsEditor instructions={instructions} onChange={onChange} />
    );
    const rows = screen.getAllByTestId("property-instruction-row");
    // Click the remove button on the first row
    const removeBtn = within(rows[0]!).getAllByRole("button").find(
      (btn) => btn.querySelector("svg")
    );
    if (removeBtn) await user.click(removeBtn);
    expect(onChange).toHaveBeenCalled();
  });

  it("shows advanced mapping toggle", () => {
    const instructions: PropertyInstruction[] = [
      { name: "test", valueString: "val", scope: "step", override: true },
    ];
    renderWithProviders(
      <PropertyInstructionsEditor instructions={instructions} onChange={onChange} />
    );
    expect(screen.getByText("Object Path Mapping")).toBeInTheDocument();
  });

  it("expands advanced fields when instruction has fromObjectPath", () => {
    const instructions: PropertyInstruction[] = [
      {
        name: "mapped",
        valueString: "",
        scope: "conversation",
        override: true,
        fromObjectPath: "response.data",
        toObjectPath: "context.result",
      },
    ];
    renderWithProviders(
      <PropertyInstructionsEditor instructions={instructions} onChange={onChange} />
    );
    expect(screen.getByDisplayValue("response.data")).toBeInTheDocument();
    expect(screen.getByDisplayValue("context.result")).toBeInTheDocument();
  });

  it("hides add button and remove buttons in readOnly mode", () => {
    const instructions: PropertyInstruction[] = [
      { name: "test", valueString: "val", scope: "step", override: true },
    ];
    renderWithProviders(
      <PropertyInstructionsEditor instructions={instructions} onChange={onChange} readOnly />
    );
    expect(screen.queryByText("Add Property Instruction")).not.toBeInTheDocument();
  });
});

// ─── OutputBuildInstructionsEditor (standalone) ──────────────────────

describe("OutputBuildInstructionsEditor", () => {
  const onChange = vi.fn();

  beforeEach(() => vi.clearAllMocks());

  it("shows empty message when no instructions", () => {
    renderWithProviders(
      <OutputBuildInstructionsEditor instructions={[]} onChange={onChange} />
    );
    expect(screen.getByText("No output build instructions")).toBeInTheDocument();
  });

  it("adds an output instruction when add is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <OutputBuildInstructionsEditor instructions={[]} onChange={onChange} />
    );
    await user.click(screen.getByText("Add Output Instruction"));
    expect(onChange).toHaveBeenCalledWith([
      expect.objectContaining({ iterationObjectName: "obj", outputType: "text" }),
    ]);
  });

  it("renders existing instruction fields", () => {
    const instructions: OutputBuildingInstruction[] = [
      {
        pathToTargetArray: "outputs.data",
        iterationObjectName: "item",
        outputType: "text",
        outputValue: "{item.text}",
        templateFilterExpression: "item.visible",
      },
    ];
    renderWithProviders(
      <OutputBuildInstructionsEditor instructions={instructions} onChange={onChange} />
    );
    expect(screen.getByDisplayValue("outputs.data")).toBeInTheDocument();
    expect(screen.getByDisplayValue("item")).toBeInTheDocument();
    expect(screen.getByDisplayValue("{item.text}")).toBeInTheDocument();
    expect(screen.getByDisplayValue("item.visible")).toBeInTheDocument();
  });

  it("shows output type select with text/image/other options", () => {
    const instructions: OutputBuildingInstruction[] = [
      { outputType: "text", outputValue: "" },
    ];
    renderWithProviders(
      <OutputBuildInstructionsEditor instructions={instructions} onChange={onChange} />
    );
    const row = screen.getByTestId("output-build-instruction-row");
    const select = within(row).getByRole("combobox") as HTMLSelectElement;
    expect(select.value).toBe("text");
    const options = within(select).getAllByRole("option");
    expect(options).toHaveLength(3);
  });

  it("hides add button in readOnly mode", () => {
    renderWithProviders(
      <OutputBuildInstructionsEditor instructions={[]} onChange={onChange} readOnly />
    );
    expect(screen.queryByText("Add Output Instruction")).not.toBeInTheDocument();
  });
});

// ─── QrBuildInstructionsEditor (standalone) ──────────────────────────

describe("QrBuildInstructionsEditor", () => {
  const onChange = vi.fn();

  beforeEach(() => vi.clearAllMocks());

  it("shows empty message when no instructions", () => {
    renderWithProviders(
      <QrBuildInstructionsEditor instructions={[]} onChange={onChange} />
    );
    expect(screen.getByText("No quick reply build instructions")).toBeInTheDocument();
  });

  it("adds a QR instruction when add is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <QrBuildInstructionsEditor instructions={[]} onChange={onChange} />
    );
    await user.click(screen.getByText("Add Quick Reply Instruction"));
    expect(onChange).toHaveBeenCalledWith([
      expect.objectContaining({
        pathToTargetArray: "",
        iterationObjectName: "obj",
        quickReplyValue: "",
        quickReplyExpressions: "",
      }),
    ]);
  });

  it("renders existing QR instruction fields", () => {
    const instructions: QuickRepliesBuildingInstruction[] = [
      {
        pathToTargetArray: "qr.data",
        iterationObjectName: "qr",
        quickReplyValue: "{qr.label}",
        quickReplyExpressions: "{qr.expr}",
      },
    ];
    renderWithProviders(
      <QrBuildInstructionsEditor instructions={instructions} onChange={onChange} />
    );
    expect(screen.getByDisplayValue("qr.data")).toBeInTheDocument();
    expect(screen.getByDisplayValue("{qr.label}")).toBeInTheDocument();
    expect(screen.getByDisplayValue("{qr.expr}")).toBeInTheDocument();
  });

  it("hides add button in readOnly mode", () => {
    renderWithProviders(
      <QrBuildInstructionsEditor instructions={[]} onChange={onChange} readOnly />
    );
    expect(screen.queryByText("Add Quick Reply Instruction")).not.toBeInTheDocument();
  });
});
