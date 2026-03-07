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

export const handlers = [
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

  // --- Resource Stores ---
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
  ];
}
