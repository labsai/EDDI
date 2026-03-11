import { http, HttpResponse } from "msw";

const BOTS_MOCK = [
  {
    resource: "eddi://ai.labs.bot/botstore/bots/bot1?version=1",
    name: "Support Bot",
    description: "Customer support assistant",
    createdOn: Date.now() - 86400000,
    lastModifiedOn: Date.now(),
  },
  {
    resource: "eddi://ai.labs.bot/botstore/bots/bot2?version=1",
    name: "FAQ Bot",
    description: "Frequently asked questions",
    createdOn: Date.now() - 172800000,
    lastModifiedOn: Date.now() - 3600000,
  },
];

const PACKAGES_MOCK = [
  {
    resource: "eddi://ai.labs.package/packagestore/packages/pkg1?version=1",
    name: "Support Package",
    description: "Package for support bot",
    createdOn: Date.now() - 86400000,
    lastModifiedOn: Date.now(),
  },
  {
    resource: "eddi://ai.labs.package/packagestore/packages/pkg2?version=1",
    name: "FAQ Package",
    description: "Package for FAQ bot",
    createdOn: Date.now() - 172800000,
    lastModifiedOn: Date.now() - 7200000,
  },
];

const CONVERSATIONS_MOCK = [
  {
    resource:
      "eddi://ai.labs.conversation/conversationstore/conversations/conv1",
    name: "",
    description: "",
    createdOn: Date.now() - 3600000,
    lastModifiedOn: Date.now() - 600000,
    botId: "bot1",
    botVersion: 1,
    conversationState: "READY",
  },
  {
    resource:
      "eddi://ai.labs.conversation/conversationstore/conversations/conv2",
    name: "",
    description: "",
    createdOn: Date.now() - 86400000,
    lastModifiedOn: Date.now() - 3600000,
    botId: "bot2",
    botVersion: 1,
    conversationState: "ENDED",
  },
];

// --- Enriched JSON Schemas (matching victools Draft 2020-12 output) ---
const RESOURCE_SCHEMAS: Record<string, object> = {
  behavior: {
    $schema: "https://json-schema.org/draft/2020-12/schema",
    type: "object",
    title: "BehaviorConfiguration",
    properties: {
      appendActions: { type: "boolean", description: "Whether to append actions from behavior rules to existing actions" },
      expressionsAsActions: { type: "boolean", description: "Whether to use expressions as actions" },
      behaviorGroups: {
        type: "array",
        description: "Groups of behavior rules",
        items: {
          type: "object",
          properties: {
            name: { type: "string", description: "Name of the behavior group" },
            executionStrategy: { type: "string", description: "Execution strategy: currentStepOnly or allSteps" },
            behaviorRules: {
              type: "array",
              items: {
                type: "object",
                properties: {
                  name: { type: "string", description: "Rule name" },
                  actions: { type: "array", items: { type: "string" }, description: "Actions to trigger" },
                  conditions: { type: "array", items: { type: "object" }, description: "Conditions to evaluate" },
                },
              },
            },
          },
        },
      },
    },
  },
  httpcalls: {
    $schema: "https://json-schema.org/draft/2020-12/schema",
    type: "object",
    title: "HttpCallsConfiguration",
    properties: {
      targetServerUrl: { type: "string", description: "Base URL of the target server" },
      httpCalls: {
        type: "array",
        description: "List of HTTP call definitions",
        items: {
          type: "object",
          properties: {
            name: { type: "string", description: "Name of the HTTP call" },
            description: { type: "string", description: "Description of what this call does" },
            actions: { type: "array", items: { type: "string" }, description: "Actions that trigger this call" },
            saveResponse: { type: "boolean", description: "Whether to save the response" },
            responseObjectName: { type: "string", description: "Key to store response under" },
            fireAndForget: { type: "boolean", description: "Whether to wait for response" },
            request: {
              type: "object",
              properties: {
                path: { type: "string", description: "URL path (supports Thymeleaf templates)" },
                method: { type: "string", description: "HTTP method (GET, POST, PUT, DELETE, PATCH)" },
                headers: { type: "object", additionalProperties: { type: "string" } },
                contentType: { type: "string" },
                body: { type: "string" },
                queryParams: { type: "object", additionalProperties: { type: "string" } },
              },
            },
            preRequest: { type: "object", properties: { propertyInstructions: { type: "array" } } },
            postResponse: {
              type: "object",
              properties: {
                propertyInstructions: { type: "array" },
                outputBuildInstructions: { type: "array" },
                qrBuildInstructions: { type: "array" },
              },
            },
          },
        },
      },
    },
  },
  output: {
    $schema: "https://json-schema.org/draft/2020-12/schema",
    type: "object",
    title: "OutputConfigurationSet",
    properties: {
      lang: { type: "string", description: "Language code (e.g. en, de, fr)" },
      outputSet: {
        type: "array",
        description: "List of output configurations",
        items: {
          type: "object",
          properties: {
            action: { type: "string", description: "Action that triggers this output" },
            timesOccurred: { type: "integer", description: "Number of occurrences to match (0 = any)" },
            outputs: {
              type: "array",
              items: {
                type: "object",
                properties: {
                  valueAlternatives: {
                    type: "array",
                    items: {
                      type: "object",
                      properties: {
                        type: { type: "string", description: "Output type: text, image, quickReply, button, etc." },
                        text: { type: "string" },
                        delay: { type: "integer" },
                      },
                    },
                  },
                },
              },
            },
            quickReplies: {
              type: "array",
              items: {
                type: "object",
                properties: {
                  value: { type: "string", description: "Display text" },
                  expressions: { type: "string", description: "Expression to evaluate" },
                  isDefault: { type: "boolean" },
                },
              },
            },
          },
        },
      },
    },
  },
  dictionary: {
    $schema: "https://json-schema.org/draft/2020-12/schema",
    type: "object",
    title: "RegularDictionaryConfiguration",
    properties: {
      lang: { type: "string", description: "Language code" },
      words: {
        type: "array",
        description: "Word definitions",
        items: {
          type: "object",
          description: "A word definition of the dictionary.",
          properties: {
            word: { type: "string", description: "A word of a natural language that you want the parser to recognize (e.g. hello)." },
            expressions: { type: "string", description: "Prolog like expressions describing the meaning of this word (e.g. greeting(hello))" },
            frequency: { type: "integer", description: "Word frequency weight" },
          },
        },
      },
      phrases: {
        type: "array",
        description: "Phrase definitions",
        items: {
          type: "object",
          description: "A phrase definition of the dictionary.",
          properties: {
            phrase: { type: "string", description: "A phrase to recognize (e.g. good morning)." },
            expressions: { type: "string", description: "Prolog like expressions describing the meaning" },
          },
        },
      },
      regExs: {
        type: "array",
        description: "Regular expression definitions",
        items: {
          type: "object",
          description: "A RegEx definition of the dictionary.",
          properties: {
            regEx: { type: "string", description: "A regular expression pattern" },
            expressions: { type: "string", description: "Prolog like expressions describing the meaning" },
          },
        },
      },
    },
  },
  langchain: {
    $schema: "https://json-schema.org/draft/2020-12/schema",
    type: "object",
    title: "LangChainConfiguration",
    properties: {
      tasks: {
        type: "array",
        description: "List of LangChain tasks",
        items: {
          type: "object",
          properties: {
            id: { type: "string", description: "Unique task identifier" },
            type: { type: "string", description: "LLM provider: openai, anthropic, gemini, ollama, huggingface" },
            description: { type: "string", description: "Task description" },
            actions: { type: "array", items: { type: "string" }, description: "Actions that trigger this task" },
            parameters: {
              type: "object",
              description: "Provider-specific parameters",
              properties: {
                systemMessage: { type: "string", description: "System prompt for the LLM" },
                addToOutput: { type: "string", description: "Whether to add response to output" },
                logSizeLimit: { type: "string", description: "Maximum conversation log entries" },
              },
              additionalProperties: { type: "string" },
            },
            tools: { type: "array", items: { type: "string" }, description: "URIs to HTTP calls configs used as tools" },
            enableBuiltInTools: { type: "boolean", description: "Whether to enable built-in tools" },
            builtInToolsWhitelist: { type: "array", items: { type: "string" }, description: "Whitelist of built-in tool names" },
            conversationHistoryLimit: { type: "integer", description: "Max conversation history entries sent to LLM" },
            maxBudgetPerConversation: { type: "number", description: "Maximum cost budget per conversation" },
            enableCostTracking: { type: "boolean" },
            enableToolCaching: { type: "boolean" },
            enableRateLimiting: { type: "boolean" },
            defaultRateLimit: { type: "integer", description: "Default rate limit per minute" },
          },
        },
      },
    },
  },
  propertysetter: {
    $schema: "https://json-schema.org/draft/2020-12/schema",
    type: "object",
    title: "PropertySetterConfiguration",
    properties: {
      setOnActions: {
        type: "array",
        description: "List of property setter definitions triggered by actions",
        items: {
          type: "object",
          properties: {
            actions: { type: "array", items: { type: "string" }, description: "Actions that trigger this setter" },
            setProperties: {
              type: "array",
              items: {
                type: "object",
                properties: {
                  name: { type: "string", description: "Property name" },
                  valueString: { type: "string", description: "Value to set (supports Thymeleaf expressions)" },
                  scope: { type: "string", description: "Scope: conversation, longTerm, or step" },
                  fromObjectPath: { type: "string", description: "Path to read value from" },
                  override: { type: "boolean", description: "Whether to override existing value" },
                },
              },
            },
          },
        },
      },
    },
  },
};

export const handlers = [
  // JSON Schema endpoints for bots and packages
  http.get("*/botstore/bots/jsonSchema", () => {
    return HttpResponse.json({
      $schema: "https://json-schema.org/draft/2020-12/schema",
      type: "object",
      title: "BotConfiguration",
      properties: {
        packages: {
          type: "array",
          description: "List of package URIs that make up this bot",
          items: { type: "string", format: "uri" },
        },
        channels: {
          type: "array",
          description: "Channel connectors for this bot",
          items: {
            type: "object",
            properties: {
              type: { type: "string", description: "Channel type identifier" },
              config: { type: "object", additionalProperties: true, description: "Channel-specific configuration" },
            },
          },
        },
      },
    });
  }),
  http.get("*/packagestore/packages/jsonSchema", () => {
    return HttpResponse.json({
      $schema: "https://json-schema.org/draft/2020-12/schema",
      type: "object",
      title: "PackageConfiguration",
      properties: {
        packageExtensions: {
          type: "array",
          description: "List of extensions in this package",
          items: {
            type: "object",
            properties: {
              type: { type: "string", description: "Extension type (e.g. ai.labs.behavior, ai.labs.langchain)" },
              extensions: { type: "object", additionalProperties: true, description: "Extension-specific configuration" },
              config: {
                type: "object",
                properties: {
                  uri: { type: "string", format: "uri", description: "Resource URI for this extension" },
                },
              },
            },
          },
        },
      },
    });
  }),

  // Bot descriptors
  http.get("*/botstore/bots/descriptors", () => {
    return HttpResponse.json(BOTS_MOCK);
  }),

  // Get bot
  http.get("*/botstore/bots/:id", ({ request }) => {
    const url = new URL(request.url);
    const version = parseInt(url.searchParams.get("version") ?? "1", 10);
    return HttpResponse.json({
      packages: [
        "eddi://ai.labs.package/packagestore/packages/pkg1?version=1",
      ],
      channels: [],
      _version: version,
    });
  }),

  // Deployment status
  http.get("*/administration/:env/deploymentstatus/:botId", () => {
    return HttpResponse.json({ status: "READY" });
  }),

  // Deploy bot
  http.post("*/administration/:env/deploy/:botId", () => {
    return new HttpResponse(null, { status: 200 });
  }),

  // Undeploy bot
  http.post("*/administration/:env/undeploy/:botId", () => {
    return new HttpResponse(null, { status: 200 });
  }),

  // Update bot
  http.put("*/botstore/bots/:id", ({ request, params }) => {
    const url = new URL(request.url);
    const currentVersion = parseInt(
      url.searchParams.get("version") ?? "1",
      10
    );
    const newVersion = currentVersion + 1;
    return new HttpResponse(null, {
      status: 200,
      headers: {
        Location: `eddi://ai.labs.bot/botstore/bots/${params.id}?version=${newVersion}`,
      },
    });
  }),

  // Duplicate bot
  http.post("*/botstore/bots/:id", ({ request }) => {
    const url = new URL(request.url);
    const deepCopy = url.searchParams.get("deepCopy");
    const newId = `dup-${Date.now()}`;
    return new HttpResponse(null, {
      status: 201,
      headers: {
        Location: `eddi://ai.labs.bot/botstore/bots/${newId}?version=1${deepCopy ? "&deepCopy=true" : ""}`,
      },
    });
  }),

  // Delete bot
  http.delete("*/botstore/bots/:id", () => {
    return new HttpResponse(null, { status: 204 });
  }),

  // Package descriptors
  http.get("*/packagestore/packages/descriptors", () => {
    return HttpResponse.json(PACKAGES_MOCK);
  }),

  // Get package
  http.get("*/packagestore/packages/:id", () => {
    return HttpResponse.json({
      packageExtensions: [
        {
          type: "ai.labs.behavior",
          extensions: {},
          config: {
            uri: "eddi://ai.labs.behavior/behaviorstore/behaviorsets/beh1?version=1",
          },
        },
        {
          type: "ai.labs.langchain",
          extensions: {},
          config: {
            uri: "eddi://ai.labs.langchain/langchainstore/langchains/lc1?version=1",
          },
        },
      ],
    });
  }),

  // Create package
  http.post("*/packagestore/packages", () => {
    return new HttpResponse(null, {
      status: 201,
      headers: { Location: "/packagestore/packages/new-pkg?version=1" },
    });
  }),

  // Delete package
  http.delete("*/packagestore/packages/:id", () => {
    return new HttpResponse(null, { status: 204 });
  }),

  // Conversation descriptors
  http.get("*/conversationstore/conversations", ({ request }) => {
    const url = new URL(request.url);
    const conversationId = url.searchParams.get("conversationId");
    if (conversationId) {
      return HttpResponse.json(
        CONVERSATIONS_MOCK.filter((c) =>
          c.resource.includes(conversationId)
        )
      );
    }
    return HttpResponse.json(CONVERSATIONS_MOCK);
  }),

  // Simple conversation log
  http.get("*/conversationstore/conversations/simple/:id", () => {
    return HttpResponse.json({
      botId: "bot1",
      botVersion: 1,
      conversationId: "conv1",
      conversationState: "READY",
      environment: "unrestricted",
      conversationSteps: [
        {
          input: "Hello",
          output: "Hi there! How can I help you?",
          actions: ["greet"],
        },
        {
          input: "What's the weather?",
          output: "The weather in NYC is sunny at 72°F.",
          actions: ["get_weather"],
        },
      ],
      conversationProperties: {
        botName: "Support Bot",
      },
    });
  }),

  // Raw conversation log
  http.get("*/conversationstore/conversations/:id", () => {
    return HttpResponse.json({
      botId: "bot1",
      botVersion: 1,
      conversationId: "conv1",
      conversationState: "READY",
      environment: "unrestricted",
      conversationSteps: [],
    });
  }),

  // Delete conversation
  http.delete("*/conversationstore/conversations/:id", () => {
    return new HttpResponse(null, { status: 204 });
  }),

  // --- Chat / Bot Engine ---

  // Start conversation
  http.post("*/bots/:env/:botId", ({ params }) => {
    return new HttpResponse(null, {
      status: 201,
      headers: {
        Location: `/bots/${params.env}/${params.botId}/conv-${Date.now()}`,
      },
    });
  }),

  // Send message (text/plain or JSON) — returns snapshot
  http.post("*/bots/:env/:botId/:conversationId", () => {
    return HttpResponse.json({
      botId: "bot1",
      botVersion: 1,
      conversationId: "conv-mock",
      conversationState: "READY",
      environment: "unrestricted",
      conversationSteps: [
        {
          input: "",
          output: "Thanks for your message! How can I help?",
          actions: ["respond"],
        },
      ],
    });
  }),

  // Read conversation (GET)
  http.get("*/bots/:env/:botId/:conversationId", () => {
    return HttpResponse.json({
      botId: "bot1",
      botVersion: 1,
      conversationId: "conv-mock",
      conversationState: "READY",
      environment: "unrestricted",
      conversationSteps: [
        {
          output: "Welcome! How can I help you today?",
          actions: ["welcome"],
        },
      ],
      conversationProperties: {
        botName: "Support Bot",
      },
    });
  }),

  // --- Backup / Import / Export ---
  http.post("*/backup/export/:botId", () => {
    return new HttpResponse(null, {
      status: 200,
      headers: { Location: "/backup/export/test-bot-1.zip" },
    });
  }),

  http.get("*/backup/export/:filename", () => {
    return new HttpResponse(new Blob(["fake-zip"]), {
      status: 200,
      headers: { "Content-Type": "application/zip" },
    });
  }),

  http.post("*/backup/import", () => {
    return new HttpResponse(null, {
      status: 200,
      headers: { Location: "/botstore/bots/imported-bot?version=1" },
    });
  }),

  // --- Extension Store ---
  http.get("*/extensionstore/extensions", () => {
    return HttpResponse.json([
      { type: "ai.labs.parser", displayName: "Parser", configs: { uri: { displayName: "Resource URI", fieldType: "URI", isOptional: false, defaultValue: null } }, extensions: {} },
      { type: "ai.labs.behavior", displayName: "Behavior Rules", configs: { uri: { displayName: "Resource URI", fieldType: "URI", isOptional: false, defaultValue: null } }, extensions: {} },
      { type: "ai.labs.property", displayName: "Property Setter", configs: { uri: { displayName: "Resource URI", fieldType: "URI", isOptional: false, defaultValue: null } }, extensions: {} },
      { type: "ai.labs.httpcalls", displayName: "HTTP Calls", configs: { uri: { displayName: "Resource URI", fieldType: "URI", isOptional: false, defaultValue: null } }, extensions: {} },
      { type: "ai.labs.langchain", displayName: "LangChain", configs: { uri: { displayName: "Resource URI", fieldType: "URI", isOptional: false, defaultValue: null } }, extensions: {} },
      { type: "ai.labs.output", displayName: "Output", configs: { uri: { displayName: "Resource URI", fieldType: "URI", isOptional: false, defaultValue: null } }, extensions: {} },
      { type: "ai.labs.output.template", displayName: "Output Template", configs: { uri: { displayName: "Resource URI", fieldType: "URI", isOptional: false, defaultValue: null } }, extensions: {} },
    ]);
  }),

  // Update package
  http.put("*/packagestore/packages/:id", ({ request, params }) => {
    const url = new URL(request.url);
    const currentVersion = parseInt(
      url.searchParams.get("version") ?? "1",
      10
    );
    const newVersion = currentVersion + 1;
    return new HttpResponse(null, {
      status: 200,
      headers: {
        Location: `eddi://ai.labs.package/packagestore/packages/${params.id}?version=${newVersion}`,
      },
    });
  }),

  // --- Resource Stores ---
  // Specific handlers for behavior and httpcalls with realistic mock data
  http.get("*/behaviorstore/behaviorsets/:id", ({ request }) => {
    const url = new URL(request.url);
    const includePrevious = url.searchParams.get("includePreviousVersions");
    // Don't match descriptor endpoints
    if (url.pathname.endsWith("/descriptors") || includePrevious) {
      return;
    }
    return HttpResponse.json({
      appendActions: true,
      expressionsAsActions: false,
      behaviorGroups: [
        {
          name: "Greeting Rules",
          executionStrategy: "currentStepOnly",
          behaviorRules: [
            {
              name: "greeting_rule",
              actions: ["greet"],
              conditions: [
                {
                  type: "inputmatcher",
                  configs: {
                    expressions: "greeting(*)",
                    occurrence: "currentStep",
                  },
                },
              ],
            },
            {
              name: "fallback_rule",
              actions: ["fallback"],
              conditions: [
                {
                  type: "negation",
                  configs: {},
                  conditions: [
                    {
                      type: "actionmatcher",
                      configs: { actions: "greet" },
                    },
                  ],
                },
              ],
            },
          ],
        },
      ],
    });
  }),

  http.get("*/httpcallsstore/httpcalls/:id", ({ request }) => {
    const url = new URL(request.url);
    const includePrevious = url.searchParams.get("includePreviousVersions");
    if (url.pathname.endsWith("/descriptors") || includePrevious) {
      return;
    }
    return HttpResponse.json({
      targetServerUrl: "https://api.example.com",
      httpCalls: [
        {
          name: "get_weather",
          description: "Fetch current weather data for a city",
          parameters: { city: "City name to look up" },
          actions: ["get_weather"],
          saveResponse: true,
          responseObjectName: "weatherData",
          fireAndForget: false,
          request: {
            path: "/weather?city=[[${city}]]",
            method: "GET",
            headers: {
              Authorization: "Bearer [[${apiKey}]]",
            },
            queryParams: {},
            contentType: "application/json",
            body: "",
          },
          preRequest: { propertyInstructions: [] },
          postResponse: {
            propertyInstructions: [],
            outputBuildInstructions: [],
            qrBuildInstructions: [],
          },
        },
      ],
    });
  }),

  // LangChain mock data
  http.get("*/langchainstore/langchains/:id", ({ request }) => {
    const url = new URL(request.url);
    const includePrevious = url.searchParams.get("includePreviousVersions");
    if (url.pathname.endsWith("/descriptors") || includePrevious) return;
    return HttpResponse.json({
      tasks: [
        {
          actions: ["help", "chat"],
          id: "main-chat",
          type: "openai",
          description: "Main AI assistant task",
          parameters: {
            systemMessage: "You are a helpful assistant.",
            addToOutput: "true",
            logSizeLimit: "20",
          },
          enableBuiltInTools: true,
          builtInToolsWhitelist: ["calculator", "datetime"],
          tools: [
            "eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/weather?version=1",
          ],
          conversationHistoryLimit: 10,
          maxBudgetPerConversation: 1.0,
          enableCostTracking: true,
          enableToolCaching: true,
          enableRateLimiting: true,
          defaultRateLimit: 100,
        },
      ],
    });
  }),

  // Output mock data
  http.get("*/outputstore/outputsets/:id", ({ request }) => {
    const url = new URL(request.url);
    const includePrevious = url.searchParams.get("includePreviousVersions");
    if (url.pathname.endsWith("/descriptors") || includePrevious) return;
    return HttpResponse.json({
      lang: "en",
      outputSet: [
        {
          action: "greet",
          timesOccurred: 0,
          outputs: [
            {
              valueAlternatives: [
                { type: "text", text: "Hello! How can I help you?", delay: 0 },
                { type: "text", text: "Hi there! What can I do for you?", delay: 0 },
              ],
            },
          ],
          quickReplies: [
            { value: "Tell me more", expressions: "more_info", isDefault: false },
            { value: "Goodbye", expressions: "bye", isDefault: false },
          ],
        },
      ],
    });
  }),

  // Property setter mock data
  http.get("*/propertysetterstore/propertysetters/:id", ({ request }) => {
    const url = new URL(request.url);
    const includePrevious = url.searchParams.get("includePreviousVersions");
    if (url.pathname.endsWith("/descriptors") || includePrevious) return;
    return HttpResponse.json({
      setOnActions: [
        {
          actions: ["greet"],
          setProperties: [
            { name: "user_greeted", valueString: "true", scope: "conversation", override: true },
            { name: "greeting_count", valueString: "[[${greeting_count}]] + 1", scope: "longTerm", fromObjectPath: "", override: true },
          ],
        },
      ],
    });
  }),

  // Dictionary mock data
  http.get("*/regulardictionarystore/regulardictionaries/:id", ({ request }) => {
    const url = new URL(request.url);
    const includePrevious = url.searchParams.get("includePreviousVersions");
    if (url.pathname.endsWith("/descriptors") || includePrevious) return;
    return HttpResponse.json({
      lang: "en",
      words: [
        { word: "hello", expressions: "greeting(hello)", frequency: 0 },
        { word: "hi", expressions: "greeting(hi)", frequency: 0 },
      ],
      phrases: [
        { phrase: "good morning", expressions: "greeting(good_morning)" },
      ],
      regExs: [
        { regEx: "\\d{5}", expressions: "zipcode(zipcode)" },
      ],
    });
  }),

  // Generic descriptor handlers for all 6 resource types
  ...createResourceHandlers("behaviorstore", "behaviorsets", "behavior"),
  ...createResourceHandlers("httpcallsstore", "httpcalls", "httpcalls"),
  ...createResourceHandlers("outputstore", "outputsets", "output"),
  ...createResourceHandlers(
    "regulardictionarystore",
    "regulardictionaries",
    "dictionary"
  ),
  ...createResourceHandlers("langchainstore", "langchains", "langchain"),
  ...createResourceHandlers(
    "propertysetterstore",
    "propertysetters",
    "propertysetter"
  ),
];

function createResourceHandlers(
  store: string,
  plural: string,
  label: string
) {
  const mockDescriptors = [
    {
      resource: `eddi://ai.labs.${label}/${store}/${plural}/res1?version=1`,
      name: `${label} Config 1`,
      description: `First ${label} configuration`,
      createdOn: Date.now() - 86400000,
      lastModifiedOn: Date.now(),
    },
    {
      resource: `eddi://ai.labs.${label}/${store}/${plural}/res2?version=1`,
      name: `${label} Config 2`,
      description: `Second ${label} configuration`,
      createdOn: Date.now() - 172800000,
      lastModifiedOn: Date.now() - 3600000,
    },
  ];

  return [
    // JSON Schema endpoint
    http.get(`*/${store}/${plural}/jsonSchema`, () => {
      const schema = RESOURCE_SCHEMAS[label];
      if (schema) {
        return HttpResponse.json(schema);
      }
      return HttpResponse.json({
        $schema: "https://json-schema.org/draft/2020-12/schema",
        type: "object",
        title: `${label}Configuration`,
        properties: {},
      });
    }),
    http.get(`*/${store}/${plural}/descriptors`, ({ request }) => {
      const url = new URL(request.url);
      const includePrevious = url.searchParams.get("includePreviousVersions");
      const filter = url.searchParams.get("filter");

      if (includePrevious === "true" && filter) {
        // Return multiple versions for a specific resource
        return HttpResponse.json([
          {
            resource: `eddi://ai.labs.${label}/${store}/${plural}/${filter}?version=2`,
            name: `${label} Config`,
            description: `${label} configuration`,
            createdOn: Date.now() - 86400000,
            lastModifiedOn: Date.now(),
          },
          {
            resource: `eddi://ai.labs.${label}/${store}/${plural}/${filter}?version=1`,
            name: `${label} Config`,
            description: `${label} configuration`,
            createdOn: Date.now() - 172800000,
            lastModifiedOn: Date.now() - 86400000,
          },
        ]);
      }
      return HttpResponse.json(mockDescriptors);
    }),
    http.get(`*/${store}/${plural}/:id`, () => {
      return HttpResponse.json({ type: label, config: {} });
    }),
    http.post(`*/${store}/${plural}`, () => {
      return new HttpResponse(null, {
        status: 201,
        headers: {
          Location: `/${store}/${plural}/new-res?version=1`,
        },
      });
    }),
    http.put(`*/${store}/${plural}/:id`, ({ request, params }) => {
      const url = new URL(request.url);
      const currentVersion = parseInt(
        url.searchParams.get("version") ?? "1",
        10
      );
      const newVersion = currentVersion + 1;
      return new HttpResponse(null, {
        status: 200,
        headers: {
          Location: `eddi://ai.labs.${label}/${store}/${plural}/${params.id}?version=${newVersion}`,
        },
      });
    }),
    http.delete(`*/${store}/${plural}/:id`, () => {
      return new HttpResponse(null, { status: 204 });
    }),
    // Duplicate resource (POST with :id)
    http.post(`*/${store}/${plural}/:id`, ({ params }) => {
      return new HttpResponse(null, {
        status: 201,
        headers: {
          Location: `/${store}/${plural}/dup-${params.id}?version=1`,
        },
      });
    }),
  ];
}

// --- Coordinator Admin Mock Data ---
const COORDINATOR_STATUS_MOCK = {
  coordinatorType: "in-memory",
  connected: true,
  connectionStatus: "CONNECTED",
  activeConversations: 2,
  totalProcessed: 1247,
  totalDeadLettered: 3,
  queueDepths: {
    "conv-abc123": 2,
    "conv-def456": 1,
  },
};

const DEAD_LETTERS_MOCK = [
  {
    id: "1",
    conversationId: "conv-fail-001",
    error: "Connection timeout to external API",
    timestamp: Date.now() - 3600000,
    payload: '{"conversationId":"conv-fail-001","error":"Connection timeout to external API","timestamp":' + (Date.now() - 3600000) + '}',
  },
  {
    id: "2",
    conversationId: "conv-fail-002",
    error: "LLM rate limit exceeded",
    timestamp: Date.now() - 7200000,
    payload: '{"conversationId":"conv-fail-002","error":"LLM rate limit exceeded","timestamp":' + (Date.now() - 7200000) + '}',
  },
  {
    id: "3",
    conversationId: "conv-fail-003",
    error: "NullPointerException in BehaviorRulesEvaluationTask",
    timestamp: Date.now() - 86400000,
    payload: '{"conversationId":"conv-fail-003","error":"NullPointerException in BehaviorRulesEvaluationTask","timestamp":' + (Date.now() - 86400000) + '}',
  },
];

export const coordinatorHandlers = [
  http.get("*/administration/coordinator/status", () => {
    return HttpResponse.json(COORDINATOR_STATUS_MOCK);
  }),

  http.get("*/administration/coordinator/dead-letters", () => {
    return HttpResponse.json(DEAD_LETTERS_MOCK);
  }),

  http.post("*/administration/coordinator/dead-letters/:entryId/replay", () => {
    return new HttpResponse(null, { status: 200 });
  }),

  http.delete("*/administration/coordinator/dead-letters/:entryId", () => {
    return new HttpResponse(null, { status: 204 });
  }),

  http.delete("*/administration/coordinator/dead-letters", () => {
    return HttpResponse.json(0);
  }),
];

