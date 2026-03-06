import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  getPackageDescriptors,
  getPackage,
} from "@/lib/api/packages";

const PACKAGES_KEY = ["packages"] as const;

export function usePackageDescriptors(limit = 100, index = 0, filter = "") {
  return useQuery({
    queryKey: [...PACKAGES_KEY, "descriptors", { limit, index, filter }],
    queryFn: () => getPackageDescriptors(limit, index, filter),
  });
}

export function usePackage(id: string, version: number) {
  return useQuery({
    queryKey: [...PACKAGES_KEY, id, version],
    queryFn: () => getPackage(id, version),
    enabled: !!id && version > 0,
  });
}

export function useUpdateBotPackages() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({
      botId,
      version,
      packages,
    }: {
      botId: string;
      version: number;
      packages: string[];
    }) => {
      const { updateBot } = await import("@/lib/api/bots");
      return updateBot(botId, version, { packages });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["bots"] });
    },
  });
}
