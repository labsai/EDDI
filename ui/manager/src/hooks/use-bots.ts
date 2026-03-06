import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  getBotDescriptors,
  getBot,
  createBot,
  deleteBot,
  duplicateBot,
  deployBot,
  undeployBot,
  getDeploymentStatus,
  type Bot,
  type BotDescriptor,
  parseResourceUri,
} from "@/lib/api/bots";

const BOTS_KEY = ["bots"] as const;

export function useBotDescriptors(
  limit = 20,
  index = 0,
  filter = ""
) {
  return useQuery({
    queryKey: [...BOTS_KEY, "descriptors", { limit, index, filter }],
    queryFn: () => getBotDescriptors(limit, index, filter),
  });
}

export function useBot(id: string, version?: number) {
  return useQuery({
    queryKey: [...BOTS_KEY, id, version],
    queryFn: () => getBot(id, version),
    enabled: !!id,
  });
}

export function useDeploymentStatus(botId: string, environment = "unrestricted") {
  return useQuery({
    queryKey: [...BOTS_KEY, "deployment", environment, botId],
    queryFn: () => getDeploymentStatus(environment, botId),
    enabled: !!botId,
    refetchInterval: (query) => {
      // Poll every 3s while deploying
      return query.state.data?.status === "IN_PROGRESS" ? 3000 : false;
    },
  });
}

export function useCreateBot() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (bot: Bot) => createBot(bot),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: BOTS_KEY });
    },
  });
}

export function useDeleteBot() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, version }: { id: string; version: number }) =>
      deleteBot(id, version),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: BOTS_KEY });
    },
  });
}

export function useDuplicateBot() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      id,
      version,
      deepCopy,
    }: {
      id: string;
      version: number;
      deepCopy?: boolean;
    }) => duplicateBot(id, version, deepCopy),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: BOTS_KEY });
    },
  });
}

export function useDeployBot() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      environment = "unrestricted",
      botId,
      version,
    }: {
      environment?: string;
      botId: string;
      version?: number;
    }) => deployBot(environment, botId, version),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: BOTS_KEY });
    },
  });
}

export function useUndeployBot() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      environment = "unrestricted",
      botId,
    }: {
      environment?: string;
      botId: string;
    }) => undeployBot(environment, botId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: BOTS_KEY });
    },
  });
}

/** Group bot descriptors by name, keeping the latest version */
export function groupBotsByName(
  bots: BotDescriptor[]
): (BotDescriptor & { id: string; version: number })[] {
  const grouped = new Map<
    string,
    BotDescriptor & { id: string; version: number }
  >();

  for (const bot of bots) {
    const { id, version } = parseResourceUri(bot.resource);
    const existing = grouped.get(bot.name);
    if (!existing || version > existing.version) {
      grouped.set(bot.name, { ...bot, id, version });
    }
  }

  return Array.from(grouped.values()).sort(
    (a, b) => b.lastModifiedOn - a.lastModifiedOn
  );
}
