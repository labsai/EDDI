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

export const handlers = [
  // Bot descriptors
  http.get("*/descriptorstore/bots", () => {
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
];
