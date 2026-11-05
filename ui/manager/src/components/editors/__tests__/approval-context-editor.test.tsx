import { useState } from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { ApprovalContextEditor } from "@/components/editors/approval-context-editor";
import { HitlConfigSection } from "@/components/editors/agent-config-sections";
import type { ApprovalContextBlock } from "@/lib/api/hitl";
import type { Agent } from "@/lib/api/agents";
import {
  approvalContextHasErrors,
  validateApprovalContextBlock,
} from "@/lib/hitl-approval-context";

function Harness({
  initial = null,
  scope = "agent",
  onValue,
}: {
  initial?: ApprovalContextBlock[] | null;
  scope?: "agent" | "rule" | "group";
  onValue?: (v: ApprovalContextBlock[] | null) => void;
}) {
  const [value, setValue] = useState<ApprovalContextBlock[] | null>(initial);
  return (
    <ApprovalContextEditor
      value={value}
      scope={scope}
      onChange={(next) => {
        setValue(next);
        onValue?.(next);
      }}
    />
  );
}

const ok = (n: number): ApprovalContextBlock => ({ label: `L${n}`, template: `{properties.x${n}}` });

describe("ApprovalContextEditor", () => {
  it("starts empty and adds a block", () => {
    const seen = vi.fn();
    renderWithProviders(<Harness onValue={seen} />);
    expect(screen.queryByTestId("approval-context-block-0")).toBeNull();

    fireEvent.click(screen.getByTestId("approval-context-add"));

    expect(screen.getByTestId("approval-context-block-0")).toBeTruthy();
    expect(seen).toHaveBeenLastCalledWith([{ label: "", template: "", maxChars: null }]);
  });

  it("edits label, template and max characters", () => {
    const seen = vi.fn();
    renderWithProviders(<Harness initial={[ok(0)]} onValue={seen} />);

    fireEvent.change(screen.getByTestId("approval-context-label-0"), { target: { value: "Draft" } });
    fireEvent.change(screen.getByTestId("approval-context-template-0"), { target: { value: "{properties.gtm.draft}" } });
    fireEvent.change(screen.getByTestId("approval-context-max-0"), { target: { value: "1200" } });

    expect(seen).toHaveBeenLastCalledWith([{ label: "Draft", template: "{properties.gtm.draft}", maxChars: 1200 }]);
  });

  it("removing the last block saves ABSENT (null), not an empty list", () => {
    const seen = vi.fn();
    renderWithProviders(<Harness initial={[ok(0)]} onValue={seen} />);

    fireEvent.click(screen.getByTestId("approval-context-remove-0"));

    expect(seen).toHaveBeenLastCalledWith(null);
    expect(screen.queryByTestId("approval-context-block-0")).toBeNull();
  });

  it("removing one of several keeps the others in order", () => {
    const seen = vi.fn();
    renderWithProviders(<Harness initial={[ok(0), ok(1), ok(2)]} onValue={seen} />);

    fireEvent.click(screen.getByTestId("approval-context-remove-1"));

    expect(seen).toHaveBeenLastCalledWith([ok(0), ok(2)]);
  });

  it("stops adding at the scope's limit (8 for an agent, 4 for a rule) and says so", () => {
    const eight = Array.from({ length: 8 }, (_, i) => ok(i));
    const { unmount } = renderWithProviders(<Harness initial={eight} />);
    expect((screen.getByTestId("approval-context-add") as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByTestId("approval-context-limit").textContent).toBe("At most 8 blocks.");
    expect(screen.getByTestId("approval-context-count").textContent).toBe("8 of 8");
    unmount();

    renderWithProviders(<Harness scope="rule" initial={eight.slice(0, 4)} />);
    expect((screen.getByTestId("approval-context-add") as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByTestId("approval-context-limit").textContent).toBe("At most 4 blocks.");
  });

  it("limits label to 80 and template to 4,000 characters in the inputs", () => {
    renderWithProviders(<Harness initial={[ok(0)]} />);
    expect((screen.getByTestId("approval-context-label-0") as HTMLInputElement).maxLength).toBe(80);
    expect((screen.getByTestId("approval-context-template-0") as HTMLTextAreaElement).maxLength).toBe(4000);
  });

  it("shows what is wrong with a block as it is typed", () => {
    renderWithProviders(<Harness initial={[{ label: "", template: "" }]} />);
    expect(screen.getByTestId("approval-context-error-0").textContent).toBe("Give the block a label.");

    fireEvent.change(screen.getByTestId("approval-context-label-0"), { target: { value: "Draft" } });
    expect(screen.getByTestId("approval-context-error-0").textContent).toBe("Write a template.");

    fireEvent.change(screen.getByTestId("approval-context-template-0"), { target: { value: "{vault:openai}" } });
    expect(screen.getByTestId("approval-context-error-0").textContent).toMatch(/vault:/);

    fireEvent.change(screen.getByTestId("approval-context-template-0"), { target: { value: "{properties.x}" } });
    fireEvent.change(screen.getByTestId("approval-context-max-0"), { target: { value: "10" } });
    expect(screen.getByTestId("approval-context-error-0").textContent).toBe("Max characters must be between 50 and 2900.");

    fireEvent.change(screen.getByTestId("approval-context-max-0"), { target: { value: "500" } });
    expect(screen.queryByTestId("approval-context-error-0")).toBeNull();
  });

  it("call.* is refused outside a rule and accepted inside one", () => {
    const block = { label: "Post", template: "{call.args.text}" };
    expect(validateApprovalContextBlock(block, "agent")).toBe("callOutsideRule");
    expect(validateApprovalContextBlock(block, "group")).toBe("callOutsideRule");
    expect(validateApprovalContextBlock(block, "rule")).toBeNull();
  });

  it("a nested call property is not the call root: {properties.call.summary} is fine outside a rule", () => {
    const block = { label: "Summary", template: "{properties.call.summary}" };
    expect(validateApprovalContextBlock(block, "agent")).toBeNull();
    expect(validateApprovalContextBlock({ label: "Post", template: "{ call.args.text}" }, "agent")).toBe("callOutsideRule");
  });

  it("mirrors the backend limits at their boundaries", () => {
    const b = (maxChars: number) => ({ label: "L", template: "{a}", maxChars });
    for (const bad of [49, 2901, 0, -1, 10.5]) expect(validateApprovalContextBlock(b(bad), "agent")).toBe("maxChars");
    for (const good of [50, 2900, 1000]) expect(validateApprovalContextBlock(b(good), "agent")).toBeNull();
    expect(approvalContextHasErrors([ok(0), { label: "", template: "x" }], "agent")).toBe(true);
    expect(approvalContextHasErrors([ok(0)], "agent")).toBe(false);
    expect(approvalContextHasErrors(null, "agent")).toBe(false);
  });
});

describe("HitlConfigSection — approval context", () => {
  const onChange = vi.fn();
  const onValidity = vi.fn();
  beforeEach(() => {
    onChange.mockReset();
    onValidity.mockReset();
  });

  it("adding a block patches hitlConfig.approvalContext", () => {
    const agent: Agent = { hitlConfig: { timeoutPolicy: "WAIT_INDEFINITELY" } };
    renderWithProviders(<HitlConfigSection agent={agent} onChange={onChange} />);

    fireEvent.click(screen.getByTestId("approval-context-add"));

    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        hitlConfig: expect.objectContaining({ approvalContext: [{ label: "", template: "", maxChars: null }] }),
      }),
    );
  });

  it("an unsavable block withholds the page's save", () => {
    const agent: Agent = { hitlConfig: { timeoutPolicy: "WAIT_INDEFINITELY", approvalContext: [{ label: "", template: "x" }] } };
    renderWithProviders(<HitlConfigSection agent={agent} onChange={onChange} onValidityChange={onValidity} />);
    expect(onValidity).toHaveBeenLastCalledWith(true);
  });

  it("a valid block does not", () => {
    const agent: Agent = { hitlConfig: { timeoutPolicy: "WAIT_INDEFINITELY", approvalContext: [ok(0)] } };
    renderWithProviders(<HitlConfigSection agent={agent} onChange={onChange} onValidityChange={onValidity} />);
    expect(onValidity).toHaveBeenLastCalledWith(false);
    expect(screen.getByTestId("approval-context-block-0")).toBeTruthy();
  });
});
