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
      undoAvailable: true,
      redoAvailable: false,
      conversationSteps: [
        {
          conversationStep: [
            { key: "input:initial", value: "Hello", timestamp: new Date().toISOString(), originPackageId: null },
            { key: "actions", value: ["greet"], timestamp: new Date().toISOString(), originPackageId: "pkg1" },
            { key: "output:text:greet", value: "Hi there! How can I help you?", timestamp: new Date().toISOString(), originPackageId: "pkg1" },
          ],
          timestamp: new Date().toISOString(),
        },
        {
          conversationStep: [
            { key: "input:initial", value: "What's the weather?", timestamp: new Date().toISOString(), originPackageId: null },
            { key: "actions", value: ["get_weather"], timestamp: new Date().toISOString(), originPackageId: "pkg1" },
            { key: "output:text:get_weather", value: "The weather in NYC is sunny at 72°F.", timestamp: new Date().toISOString(), originPackageId: "pkg1" },
          ],
          timestamp: new Date().toISOString(),
        },
      ],
      conversationOutputs: [
        { "output:text:greet": "Hi there! How can I help you?" },
        { "output:text:get_weather": "The weather in NYC is sunny at 72°F." },
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

  http.post("*/backup/import/preview", () => {
    return HttpResponse.json({
      botOriginId: "origin-bot-1",
      botName: "Weather Bot",
      resources: [
        {
          originId: "origin-bot-1",
          resourceType: "bot",
          name: "Weather Bot",
          action: "UPDATE",
          localId: "bot1",
          localVersion: 1,
        },
        {
          originId: "origin-pkg-1",
          resourceType: "package",
          name: "Main Package",
          action: "UPDATE",
          localId: "pkg1",
          localVersion: 1,
        },
        {
          originId: "origin-beh-1",
          resourceType: "behavior",
          name: "Greeting Rules",
          action: "CREATE",
          localId: null,
          localVersion: null,
        },
        {
          originId: "origin-dict-1",
          resourceType: "dictionary",
          name: "English Dictionary",
          action: "UPDATE",
          localId: "dict1",
          localVersion: 1,
        },
      ],
    });
  }),

  // NOTE: This generic handler must come AFTER the /preview handler
  http.post("*/backup/import", ({ request }) => {
    const url = new URL(request.url);
    const strategy = url.searchParams.get("strategy");
    if (strategy === "merge") {
      return new HttpResponse(null, {
        status: 200,
        headers: { Location: "/botstore/bots/bot1?version=2" },
      });
    }
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

// ─── Orphan Admin Handlers ───────────────────────────────────────────────────

const ORPHAN_REPORT_MOCK = {
  totalOrphans: 2,
  deletedCount: 0,
  orphans: [
    {
      resourceUri: "eddi://ai.labs.package/packagestore/packages/orphan1?version=1",
      type: "ai.labs.package",
      name: "Unused Package",
      deleted: false,
    },
    {
      resourceUri: "eddi://ai.labs.behavior/behaviorstore/behaviorsets/orphan2?version=1",
      type: "ai.labs.behavior",
      name: "Old Behavior Set",
      deleted: true,
    },
  ],
};

export const orphanHandlers = [
  http.get("*/administration/orphans", () => {
    return HttpResponse.json(ORPHAN_REPORT_MOCK);
  }),

  http.delete("*/administration/orphans", () => {
    return HttpResponse.json({
      ...ORPHAN_REPORT_MOCK,
      deletedCount: ORPHAN_REPORT_MOCK.totalOrphans,
    });
  }),
];

// ─── Log Admin Handlers ──────────────────────────────────────────────────────

const MOCK_LOG_ENTRIES = [
  {
    timestamp: Date.now() - 5000,
    level: "INFO",
    loggerName: "ai.labs.eddi.engine.runtime.BotEngine",
    message: "Processing conversation conv-1 for bot bot-1",
    environment: "unrestricted",
    botId: "bot-1",
    botVersion: 1,
    conversationId: "conv-1",
    userId: "user-1",
    instanceId: "eddi-host-a1b2",
  },
  {
    timestamp: Date.now() - 3000,
    level: "WARNING",
    loggerName: "ai.labs.eddi.modules.langchain.impl.LangchainTask",
    message: "LLM response took 8200ms, exceeding timeout threshold of 5000ms",
    environment: "unrestricted",
    botId: "bot-1",
    botVersion: 1,
    conversationId: "conv-1",
    userId: "user-1",
    instanceId: "eddi-host-a1b2",
  },
  {
    timestamp: Date.now() - 1000,
    level: "SEVERE",
    loggerName: "ai.labs.eddi.modules.httpcalls.impl.HttpCallsTask",
    message:
      "Failed to execute HTTP call 'get_weather'\n\tat ai.labs.eddi.modules.httpcalls.impl.HttpCallsTask.executeTask(HttpCallsTask.java:85)\n\tat ai.labs.eddi.modules.httpcalls.impl.HttpCallsTask.execute(HttpCallsTask.java:42)\n\tat ai.labs.eddi.engine.lifecycle.LifecycleManager.executeComponent(LifecycleManager.java:120)\nCaused by: java.net.ConnectException: Connection refused\n\tat java.net.http/jdk.internal.net.http.HttpClientImpl.send(HttpClientImpl.java:565)\n\tat java.net.http/jdk.internal.net.http.HttpClientImpl.send(HttpClientImpl.java:510)",
    environment: "unrestricted",
    botId: "bot-1",
    botVersion: 1,
    conversationId: "conv-1",
    userId: "user-1",
    instanceId: "eddi-host-a1b2",
  },
];

export const logAdminHandlers = [
  http.get("*/administration/logs", () => {
    return HttpResponse.json(MOCK_LOG_ENTRIES);
  }),

  http.get("*/administration/logs/history", () => {
    return HttpResponse.json(
      MOCK_LOG_ENTRIES.map((e) => ({
        ...e,
        timestamp: new Date(e.timestamp).toISOString(),
      }))
    );
  }),

  http.get("*/administration/logs/instance", () => {
    return HttpResponse.json({ instanceId: "eddi-host-a1b2" });
  }),
];

// --- Secrets Vault Mock ---
const MOCK_SECRETS = [
  {
    tenantId: "default",
    botId: "bot1",
    keyName: "apiKey",
    createdAt: new Date(Date.now() - 86400000).toISOString(),
    lastAccessedAt: new Date(Date.now() - 3600000).toISOString(),
    checksum: "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2",
  },
  {
    tenantId: "default",
    botId: "bot1",
    keyName: "dbPassword",
    createdAt: new Date(Date.now() - 172800000).toISOString(),
    lastAccessedAt: null,
    checksum: "f6e5d4c3b2a1f6e5d4c3b2a1f6e5d4c3b2a1f6e5d4c3b2a1f6e5d4c3b2a1f6e5",
  },
];

export const secretsHandlers = [
  // List secrets
  http.get("*/secretstore/secrets/:tenantId/:botId", ({ params }) => {
    const filtered = MOCK_SECRETS.filter(
      (s) => s.tenantId === params.tenantId && s.botId === params.botId,
    );
    return HttpResponse.json(filtered);
  }),

  // Store secret
  http.put("*/secretstore/secrets/:tenantId/:botId/:keyName", ({ params }) => {
    return HttpResponse.json(
      {
        reference: `\${eddivault:${params.tenantId}.${params.botId}.${params.keyName}}`,
        tenantId: params.tenantId,
        botId: params.botId,
        keyName: params.keyName,
      },
      { status: 201 },
    );
  }),

  // Delete secret
  http.delete(
    "*/secretstore/secrets/:tenantId/:botId/:keyName",
    () => new HttpResponse(null, { status: 204 }),
  ),

  // Health check
  http.get("*/secretstore/secrets/health", () =>
    HttpResponse.json({ status: "UP", provider: "DatabaseSecretProvider", available: true }),
  ),
];

// ─── Audit Trail Handlers ────────────────────────────────────────────────────

const MOCK_AUDIT_ENTRIES = [
  {
    id: "audit-1",
    conversationId: "conv1",
    botId: "bot1",
    botVersion: 1,
    userId: "user-1",
    environment: "unrestricted",
    stepIndex: 0,
    taskId: "ai.labs.parser",
    taskType: "expressions",
    taskIndex: 0,
    durationMs: 12,
    input: { "input:initial": "Hello there" },
    output: { "expressions:parsed": ["greeting(hello)"] },
    llmDetail: null,
    toolCalls: null,
    actions: null,
    cost: 0,
    timestamp: new Date(Date.now() - 60000).toISOString(),
    hmac: "a1b2c3d4e5f6789012345678901234567890abcdef1234567890abcdef12345678",
  },
  {
    id: "audit-2",
    conversationId: "conv1",
    botId: "bot1",
    botVersion: 1,
    userId: "user-1",
    environment: "unrestricted",
    stepIndex: 0,
    taskId: "ai.labs.behavior",
    taskType: "behavior",
    taskIndex: 1,
    durationMs: 3,
    input: { "expressions:parsed": ["greeting(hello)"] },
    output: { "actions:triggered": ["greet", "chat"] },
    llmDetail: null,
    toolCalls: null,
    actions: ["greet", "chat"],
    cost: 0,
    timestamp: new Date(Date.now() - 59000).toISOString(),
    hmac: "b2c3d4e5f6a1789012345678901234567890abcdef1234567890abcdef12345678",
  },
  {
    id: "audit-3",
    conversationId: "conv1",
    botId: "bot1",
    botVersion: 1,
    userId: "user-1",
    environment: "unrestricted",
    stepIndex: 0,
    taskId: "ai.labs.langchain",
    taskType: "langchain",
    taskIndex: 2,
    durationMs: 1850,
    input: { "user:message": "Hello there", "conversation:history": 3 },
    output: { "llm:response": "Hi there! How can I help you today?" },
    llmDetail: {
      "compiled_prompt": "You are a helpful assistant.\n\nUser: Hello there",
      "model_response": "Hi there! How can I help you today?",
      "model_name": "gpt-4o-mini",
      "input_tokens": 42,
      "output_tokens": 12,
    },
    toolCalls: null,
    actions: ["greet", "chat"],
    cost: 0.003,
    timestamp: new Date(Date.now() - 57000).toISOString(),
    hmac: "c3d4e5f6a1b2789012345678901234567890abcdef1234567890abcdef12345678",
  },
  {
    id: "audit-4",
    conversationId: "conv1",
    botId: "bot1",
    botVersion: 1,
    userId: "user-1",
    environment: "unrestricted",
    stepIndex: 0,
    taskId: "ai.labs.output",
    taskType: "output",
    taskIndex: 3,
    durationMs: 2,
    input: { "actions": ["greet", "chat"] },
    output: { "output:text": "Hi there! How can I help you today?" },
    llmDetail: null,
    toolCalls: null,
    actions: ["greet", "chat"],
    cost: 0,
    timestamp: new Date(Date.now() - 55000).toISOString(),
    hmac: "d4e5f6a1b2c3789012345678901234567890abcdef1234567890abcdef12345678",
  },
];

export const auditHandlers = [
  // Get audit trail by conversation
  http.get("*/auditstore/:conversationId/count", () => {
    return HttpResponse.json(MOCK_AUDIT_ENTRIES.length);
  }),

  http.get("*/auditstore/bot/:botId", () => {
    return HttpResponse.json(MOCK_AUDIT_ENTRIES);
  }),

  http.get("*/auditstore/:conversationId", ({ params }) => {
    if (params.conversationId === "count") return;
    return HttpResponse.json(MOCK_AUDIT_ENTRIES);
  }),
];

// --- Tenant Quota Mock Data ---

const MOCK_QUOTA = {
  tenantId: "default",
  maxConversationsPerDay: -1,
  maxBotsPerTenant: -1,
  maxApiCallsPerMinute: -1,
  maxMonthlyCostUsd: -1,
  enabled: false,
};

const MOCK_USAGE = {
  tenantId: "default",
  conversationsToday: 42,
  apiCallsThisMinute: 7,
  monthlyCostUsd: 12.50,
  minuteWindowStart: new Date().toISOString(),
  dayStart: new Date(new Date().setHours(0, 0, 0, 0)).toISOString(),
};

export const quotaHandlers = [
  http.get("*/administration/quotas", () => {
    return HttpResponse.json([MOCK_QUOTA]);
  }),

  http.get("*/administration/quotas/:tenantId/usage", () => {
    return HttpResponse.json(MOCK_USAGE);
  }),

  http.get("*/administration/quotas/:tenantId", () => {
    return HttpResponse.json(MOCK_QUOTA);
  }),

  http.put("*/administration/quotas/:tenantId", async ({ request }) => {
    const body = await request.json();
    return HttpResponse.json(body);
  }),

  http.post("*/administration/quotas/:tenantId/usage/reset", () => {
    return new HttpResponse(null, { status: 200 });
  }),
];

// ─── Schedule Handlers ───────────────────────────────────────────────────────

const SCHEDULES_MOCK = [
  {
    id: "sched-1",
    name: "Daily Health Check",
    triggerType: "CRON",
    botId: "bot1",
    botVersion: 0,
    environment: "unrestricted",
    cronExpression: "0 9 * * MON-FRI",
    cronDescription: "At 09:00 AM, Monday through Friday",
    message: "health_check",
    conversationStrategy: "new",
    enabled: true,
    nextFire: Date.now() + 3600000,
    lastFired: Date.now() - 86400000,
    fireStatus: "COMPLETED",
    failCount: 0,
    createdAt: Date.now() - 604800000,
    updatedAt: Date.now() - 86400000,
  },
  {
    id: "sched-2",
    name: "Heartbeat Monitor",
    triggerType: "HEARTBEAT",
    botId: "bot2",
    botVersion: 0,
    environment: "unrestricted",
    heartbeatIntervalSeconds: 300,
    message: "ping",
    conversationStrategy: "persistent",
    enabled: true,
    nextFire: Date.now() + 60000,
    lastFired: Date.now() - 300000,
    fireStatus: "PENDING",
    failCount: 0,
    createdAt: Date.now() - 172800000,
    updatedAt: Date.now() - 300000,
  },
  {
    id: "sched-3",
    name: "Failed Report",
    triggerType: "CRON",
    botId: "bot1",
    botVersion: 0,
    environment: "unrestricted",
    cronExpression: "*/5 * * * *",
    cronDescription: "Every 5 minutes",
    message: "generate_report",
    conversationStrategy: "new",
    enabled: false,
    nextFire: null,
    lastFired: Date.now() - 7200000,
    fireStatus: "DEAD_LETTERED",
    failCount: 3,
    createdAt: Date.now() - 259200000,
    updatedAt: Date.now() - 7200000,
  },
];

const FIRE_LOGS_MOCK = [
  {
    id: "fire-1",
    scheduleId: "sched-1",
    scheduleName: "Daily Health Check",
    botId: "bot1",
    conversationId: "conv-123",
    firedAt: Date.now() - 86400000,
    completedAt: Date.now() - 86399000,
    durationMs: 1000,
    success: true,
    error: null,
  },
  {
    id: "fire-2",
    scheduleId: "sched-1",
    scheduleName: "Daily Health Check",
    botId: "bot1",
    conversationId: "conv-124",
    firedAt: Date.now() - 172800000,
    completedAt: Date.now() - 172799500,
    durationMs: 500,
    success: false,
    error: "Connection timeout",
  },
];

export const scheduleHandlers = [
  // List all schedules
  http.get("*/schedulestore/schedules", ({ request }) => {
    const url = new URL(request.url);
    const botId = url.searchParams.get("botId");
    if (botId) {
      return HttpResponse.json(SCHEDULES_MOCK.filter((s) => s.botId === botId));
    }
    return HttpResponse.json(SCHEDULES_MOCK);
  }),

  // Get single schedule
  http.get("*/schedulestore/schedules/:id", ({ params, request }) => {
    const url = new URL(request.url);
    // Skip sub-paths like /fires, /enable, etc.
    if (url.pathname.includes("/fires") || url.pathname.includes("/admin")) return;
    const schedule = SCHEDULES_MOCK.find((s) => s.id === params.id);
    if (schedule) return HttpResponse.json(schedule);
    return new HttpResponse(null, { status: 404 });
  }),

  // Create schedule
  http.post("*/schedulestore/schedules", () => {
    return new HttpResponse(null, {
      status: 201,
      headers: { Location: "/schedulestore/schedules/new-sched-1" },
    });
  }),

  // Update schedule
  http.put("*/schedulestore/schedules/:id", () => {
    return new HttpResponse(null, { status: 200 });
  }),

  // Delete schedule
  http.delete("*/schedulestore/schedules/:id", () => {
    return new HttpResponse(null, { status: 204 });
  }),

  // Enable
  http.post("*/schedulestore/schedules/:id/enable", () => {
    return new HttpResponse(null, { status: 200 });
  }),

  // Disable
  http.post("*/schedulestore/schedules/:id/disable", () => {
    return new HttpResponse(null, { status: 200 });
  }),

  // Fire now
  http.post("*/schedulestore/schedules/:id/fire", () => {
    return new HttpResponse(null, { status: 200 });
  }),

  // Fire logs
  http.get("*/schedulestore/schedules/:id/fires", () => {
    return HttpResponse.json(FIRE_LOGS_MOCK);
  }),

  // Admin - failed fires
  http.get("*/schedulestore/schedules/admin/failed", () => {
    return HttpResponse.json(FIRE_LOGS_MOCK.filter((l) => !l.success));
  }),

  // Retry dead letter
  http.post("*/schedulestore/schedules/:id/retry", () => {
    return new HttpResponse(null, { status: 200 });
  }),

  // Dismiss dead letter
  http.post("*/schedulestore/schedules/:id/dismiss", () => {
    return new HttpResponse(null, { status: 200 });
  }),
];
