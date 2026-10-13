import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { toast } from "sonner";
import { ApiClientError } from "../api-client";
import { createQueryClient } from "../query-client";

vi.mock("sonner", () => {
  const history: { type: string }[] = [];
  const error = vi.fn((message: string) => {
    void message;
    history.push({ type: "error" });
  });
  return { toast: { error, getHistory: () => history } };
});

async function failMutation(
  options: { meta?: Record<string, unknown>; onError?: () => void; handledInline?: boolean } = {},
) {
  const client = createQueryClient();
  const mutation = client.getMutationCache().build(client, {
    mutationFn: async () => {
      throw new ApiClientError(409, "Cannot purge: scan incomplete");
    },
    meta: options.meta,
    onError: options.onError,
  });
  await mutation.execute(undefined).catch(() => undefined);
  if (options.handledInline) toast.error("handled by the caller");
}

describe("global mutation error fallback", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.mocked(toast.error).mockClear();
  });
  afterEach(() => vi.useRealTimers());

  it("toasts the server's message for a mutation nobody handled", async () => {
    await failMutation();
    await vi.advanceTimersByTimeAsync(100);
    expect(toast.error).toHaveBeenCalledWith("Cannot purge: scan incomplete (HTTP 409)");
  });

  it("stays quiet when the mutation opted out via meta", async () => {
    await failMutation({ meta: { silent: true } });
    await vi.advanceTimersByTimeAsync(100);
    expect(toast.error).not.toHaveBeenCalled();
  });

  it("stays quiet when the mutation has its own onError", async () => {
    await failMutation({ onError: () => undefined });
    await vi.advanceTimersByTimeAsync(100);
    expect(toast.error).not.toHaveBeenCalled();
  });

  it("does not double up when the caller toasted in the meantime", async () => {
    const client = createQueryClient();
    const mutation = client.getMutationCache().build(client, {
      mutationFn: async () => {
        throw new ApiClientError(500, "boom");
      },
    });
    await mutation.execute(undefined).catch(() => {
      toast.error("caller's own message");
    });
    await vi.advanceTimersByTimeAsync(100);
    expect(toast.error).toHaveBeenCalledTimes(1);
    expect(toast.error).toHaveBeenCalledWith("caller's own message");
  });
});
