import { describe, it, expect, vi, afterEach } from "vitest";
import { act } from "@testing-library/react";
import { renderWithProviders } from "@eddi/app-kit/test/test-utils";
import { JsonEditor } from "@/components/editors/json-editor";

type Props = { onChange?: (v: string) => void; onMount?: (editor: unknown) => void };
let props: Props = {};

vi.mock("@monaco-editor/react", () => ({
  default: (p: Props) => {
    props = p;
    return <div data-testid="mock-monaco" />;
  },
}));

afterEach(() => vi.useRealTimers());

describe("JsonEditor — format on mount", () => {
  it("does not report the mount-time format pass as an edit", async () => {
    vi.useFakeTimers();
    const onChange = vi.fn();
    renderWithProviders(<JsonEditor value='{"a":1}' onChange={onChange} />);
    // The format action rewrites the document, which Monaco reports as a change.
    const editor = {
      getAction: () => ({
        run: async () => {
          props.onChange?.('{\n  "a": 1\n}');
        },
      }),
    };
    props.onMount?.(editor);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(200);
    });
    expect(onChange).not.toHaveBeenCalled();

    // A real edit afterwards still goes through.
    props.onChange?.('{"a":2}');
    expect(onChange).toHaveBeenCalledWith('{"a":2}');
  });
});
