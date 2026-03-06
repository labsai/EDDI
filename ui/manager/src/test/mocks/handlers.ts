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
  http.get("*/botstore/bots/:id", () => {
    return HttpResponse.json({
      packages: [
        "eddi://ai.labs.package/packagestore/packages/pkg1?version=1",
      ],
      channels: [],
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
];
