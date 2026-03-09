import { getBotDescriptors } from "./bots";
import { getPackageDescriptors } from "./packages";
import { getConversationDescriptors } from "./conversations";

export interface DashboardStats {
  botCount: number;
  packageCount: number;
  conversationCount: number;
  resourceCount: number;
}

/** Aggregate stats from existing API endpoints */
export async function getDashboardStats(): Promise<DashboardStats> {
  const [bots, packages, conversations] = await Promise.all([
    getBotDescriptors(1000, 0).catch(() => []),
    getPackageDescriptors(1000, 0).catch(() => []),
    getConversationDescriptors(1000, 0).catch(() => []),
  ]);

  return {
    botCount: bots.length,
    packageCount: packages.length,
    conversationCount: conversations.length,
    resourceCount: 0, // No single endpoint for total resources
  };
}
