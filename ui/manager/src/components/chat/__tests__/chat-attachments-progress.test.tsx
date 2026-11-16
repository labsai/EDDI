import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, renderHook, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders } from "@eddi/app-kit/test/test-utils";
import { server } from "@eddi/app-kit/test/mocks/server";
import { ChatPanel } from "@eddi/app-kit/components/chat/chat-panel";
import { PendingAttachmentChip } from "@eddi/app-kit/components/chat/attachment-chip";
import { useChatStore } from "@eddi/app-kit/hooks/use-chat";
import { useDebugStore } from "@eddi/app-kit/hooks/use-debug-events";
import { useAttachmentStaging } from "@eddi/app-kit/hooks/use-attachment-staging";
import { toast } from "sonner";
import { AttachmentError, PROGRESS_MIN_BYTES, uploadAttachment } from "@eddi/app-kit/lib/api/attachments";

/** T1.8 — per-file progress and errors, and a way to try a failed file again. */

describe("uploadAttachment with progress", () => {
  let sent: FakeXhr[];
  /** Large enough to report progress. */
  const big = (name: string, type = "") => new File([new Uint8Array(PROGRESS_MIN_BYTES)], name, { type });

  class FakeXhr {
    status = 0;
    statusText = "";
    responseText = "";
    headers: Record<string, string> = {};
    method = "";
    url = "";
    upload: { onprogress: ((e: { lengthComputable: boolean; loaded: number; total: number }) => void) | null } = {
      onprogress: null,
    };
    onload: (() => void) | null = null;
    onerror: (() => void) | null = null;
    onabort: (() => void) | null = null;
    open(method: string, url: string) {
      this.method = method;
      this.url = url;
    }
    setRequestHeader(name: string, value: string) {
      this.headers[name] = value;
    }
    getResponseHeader(name: string) {
      return name.toLowerCase() === "content-type" ? "application/json" : null;
    }
    send() {
      sent.push(this);
    }
    respond(status: number, body: unknown) {
      this.status = status;
      this.responseText = JSON.stringify(body);
      this.onload?.();
    }
  }

  beforeEach(() => {
    sent = [];
    vi.stubGlobal("XMLHttpRequest", FakeXhr);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("reports the bytes sent and resolves with the stored file", async () => {
    const progress = vi.fn();
    const upload = uploadAttachment("conv1", big("notes.txt", "text/plain"), progress);
    const xhr = sent[0]!;
    expect(xhr.method).toBe("POST");
    expect(xhr.url).toMatch(/\/conversations\/conv1\/attachments$/);
    xhr.upload.onprogress?.({ lengthComputable: true, loaded: 50, total: 200 });
    xhr.respond(201, { storageRef: "r1", filename: "notes.txt", sizeBytes: 5 });
    await expect(upload).resolves.toMatchObject({ storageRef: "r1", fileName: "notes.txt" });
    expect(progress.mock.calls.map((c) => c[0])).toEqual([0.25, 1]);
  });

  it("refuses with the server's reason, as the fetch path does", async () => {
    const upload = uploadAttachment("conv1", big("a.exe"), vi.fn());
    sent[0]!.respond(400, { error: "That file type is not accepted", code: "ATTACHMENT_REJECTED" });
    await expect(upload).rejects.toMatchObject({ message: "That file type is not accepted", status: 400, code: "ATTACHMENT_REJECTED" });
    await expect(upload).rejects.toBeInstanceOf(AttachmentError);
  });

  it("leaves a small file to fetch: nothing to show progress for", async () => {
    server.use(
      http.post("*/conversations/conv1/attachments", () =>
        HttpResponse.json({ storageRef: "small", fileName: "a.txt" }, { status: 201 }),
      ),
    );
    await expect(uploadAttachment("conv1", new File(["x"], "a.txt"), vi.fn())).resolves.toMatchObject({ storageRef: "small" });
    expect(sent).toHaveLength(0);
  });

  it("names a dropped connection", async () => {
    const upload = uploadAttachment("conv1", big("a.txt"), vi.fn());
    sent[0]!.onerror?.();
    await expect(upload).rejects.toMatchObject({ status: 0, message: expect.stringContaining("Network error") });
  });
});

describe("PendingAttachmentChip", () => {
  const file = new File(["x".repeat(10)], "report.pdf", { type: "application/pdf" });

  it("shows how much of the file has gone up", () => {
    renderWithProviders(
      <PendingAttachmentChip att={{ id: "1", file, status: "uploading", progress: 0.42 }} onRemove={vi.fn()} />,
    );
    const bar = screen.getByTestId("attachment-progress");
    expect(bar).toHaveAttribute("role", "progressbar");
    expect(bar).toHaveAttribute("aria-valuenow", "42");
    expect(screen.getByTestId("attachment-status")).toHaveTextContent("Uploading… 42%");
  });

  it("says why an upload failed, as an alert, and offers to try again", () => {
    const onRetry = vi.fn();
    renderWithProviders(
      <PendingAttachmentChip att={{ id: "1", file, status: "error", error: "Quota reached" }} onRemove={vi.fn()} onRetry={onRetry} />,
    );
    expect(screen.getByRole("alert")).toHaveTextContent("Quota reached");
    fireEvent.click(screen.getByTestId("attachment-retry"));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });
});

describe("ChatPanel — a failed attachment, tried again", () => {
  beforeAll(() => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
  });

  beforeEach(() => {
    useChatStore.getState().reset();
    useDebugStore.getState().reset();
    useChatStore.getState().setSelectedAgent("agent1", "Support Bot");
    useChatStore.getState().setConversationId("conv1");
  });

  it("keeps the chip with the server's reason, then uploads it again on request", async () => {
    let attempts = 0;
    server.use(
      http.post("*/conversations/conv1/attachments", () => {
        attempts += 1;
        return attempts === 1
          ? HttpResponse.json({ error: "Storage is busy, try again" }, { status: 503 })
          : HttpResponse.json({ storageRef: "ref-2", fileName: "photo.txt", sizeBytes: 3 }, { status: 201 });
      }),
    );
    renderWithProviders(<ChatPanel />);
    const input = screen.getByTestId("chat-file-input") as HTMLInputElement;
    fireEvent.change(input, { target: { files: [new File(["abc"], "photo.txt", { type: "text/plain" })] } });

    expect(await screen.findByRole("alert")).toHaveTextContent("Storage is busy, try again");
    fireEvent.click(screen.getByTestId("attachment-retry"));

    await waitFor(() => expect(attempts).toBe(2));
    await waitFor(() => expect(screen.queryByTestId("attachment-retry")).not.toBeInTheDocument());
    expect(screen.getByTestId("attachment-chip")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});

describe("an upload the user walks away from", () => {
  let sent: AbortableXhr[];
  const big = (name: string) => new File([new Uint8Array(PROGRESS_MIN_BYTES)], name, { type: "text/plain" });

  class AbortableXhr {
    status = 0;
    statusText = "";
    responseText = "";
    aborted = false;
    upload: { onprogress: ((e: { lengthComputable: boolean; loaded: number; total: number }) => void) | null } = {
      onprogress: null,
    };
    onload: (() => void) | null = null;
    onerror: (() => void) | null = null;
    onabort: (() => void) | null = null;
    open() {}
    setRequestHeader() {}
    getResponseHeader() {
      return "application/json";
    }
    send() {
      sent.push(this);
    }
    abort() {
      this.aborted = true;
      this.onabort?.();
    }
    respond(status: number, body: unknown) {
      this.status = status;
      this.responseText = JSON.stringify(body);
      this.onload?.();
    }
  }

  beforeEach(() => {
    sent = [];
    vi.stubGlobal("XMLHttpRequest", AbortableXhr);
    vi.spyOn(toast, "error").mockImplementation(() => "");
    vi.spyOn(toast, "warning").mockImplementation(() => "");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("aborts the request from the signal and rejects as cancelled", async () => {
    const controller = new AbortController();
    const upload = uploadAttachment("conv1", big("a.txt"), vi.fn(), controller.signal);
    controller.abort();
    expect(sent[0]!.aborted).toBe(true);
    await expect(upload).rejects.toMatchObject({ code: "UPLOAD_CANCELLED" });
  });

  it("removing the chip aborts the upload, with no error and no chip left", async () => {
    const { result } = renderHook(() => useAttachmentStaging("conv1"));
    await act(async () => {
      void result.current.stageFiles([big("a.txt")]);
    });
    expect(result.current.pendingAttachments).toHaveLength(1);
    await act(async () => {
      result.current.removeAttachment(result.current.pendingAttachments[0]!.id);
    });
    expect(sent[0]!.aborted).toBe(true);
    expect(result.current.pendingAttachments).toHaveLength(0);
    expect(toast.error).not.toHaveBeenCalled();
  });

  it("unmounting aborts the upload without an error", async () => {
    const { result, unmount } = renderHook(() => useAttachmentStaging("conv1"));
    await act(async () => {
      void result.current.stageFiles([big("a.txt")]);
    });
    unmount();
    expect(sent[0]!.aborted).toBe(true);
    await Promise.resolve();
    expect(toast.error).not.toHaveBeenCalled();
  });

  it("switching conversation aborts the upload", async () => {
    const { result, rerender } = renderHook(({ id }) => useAttachmentStaging(id), { initialProps: { id: "conv1" } });
    await act(async () => {
      void result.current.stageFiles([big("a.txt")]);
    });
    rerender({ id: "conv2" });
    expect(sent[0]!.aborted).toBe(true);
    expect(result.current.pendingAttachments).toHaveLength(0);
  });

  it("deletes a file whose reply arrives after its chip was discarded, and shows nothing", async () => {
    const deletes: string[] = [];
    server.use(
      http.delete("*/conversations/conv1/attachments/:ref", ({ params }) => {
        deletes.push(String(params.ref));
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const { result } = renderHook(() => useAttachmentStaging("conv1"));
    await act(async () => {
      void result.current.stageFiles([big("a.txt")]);
    });
    const xhr = sent[0]!;
    // An XHR whose abort() does not stop a response already on the wire.
    xhr.abort = () => {
      xhr.aborted = true;
    };
    await act(async () => {
      result.current.removeAttachment(result.current.pendingAttachments[0]!.id);
      xhr.respond(201, { storageRef: "late", fileName: "a.txt", sizeBytes: 1, forwardableInline: false });
    });
    await waitFor(() => expect(deletes).toEqual(["late"]));
    expect(result.current.pendingAttachments).toHaveLength(0);
    expect(toast.warning).not.toHaveBeenCalled();
  });

  it("still completes a normal upload", async () => {
    const { result } = renderHook(() => useAttachmentStaging("conv1"));
    await act(async () => {
      void result.current.stageFiles([big("a.txt")]);
    });
    await act(async () => {
      sent[0]!.respond(201, { storageRef: "r1", fileName: "a.txt", sizeBytes: 1 });
    });
    expect(result.current.pendingAttachments[0]).toMatchObject({ status: "ready", result: { storageRef: "r1" } });
    expect(sent[0]!.aborted).toBe(false);
  });
});
