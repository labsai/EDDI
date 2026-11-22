import { describe, it, expect, vi } from "vitest";
import { useState } from "react";
import { screen, fireEvent } from "@testing-library/react";
import { renderWithProviders } from "@eddi/app-kit/test/test-utils";
import { EmailReplyReviewSection } from "@/components/channels/email-reply-review";
import { isInternalDomainPattern, type EmailControl } from "@/lib/api/channels";

function Harness({ initial, conversation = true, onChange }: {
  initial: EmailControl | undefined;
  conversation?: boolean;
  onChange: (next: EmailControl | undefined) => void;
}) {
  const [value, setValue] = useState(initial);
  return (
    <EmailReplyReviewSection
      emailControl={value}
      conversation={conversation}
      onChange={(next) => {
        setValue(next);
        onChange(next);
      }}
    />
  );
}

describe("EmailReplyReviewSection", () => {
  it("defaults to never and writes nothing until changed", () => {
    const onChange = vi.fn();
    renderWithProviders(<Harness initial={undefined} onChange={onChange} />);

    expect(screen.getByTestId("reply-review-mode")).toHaveValue("never");
    expect(onChange).not.toHaveBeenCalled();
  });

  it("sets a mode and keeps the S/MIME settings it does not edit", () => {
    const onChange = vi.fn();
    renderWithProviders(<Harness initial={{ smime: { sign: "required", connection: "cert" } }} onChange={onChange} />);

    fireEvent.change(screen.getByTestId("reply-review-mode"), { target: { value: "external" } });

    expect(onChange).toHaveBeenLastCalledWith({ smime: { sign: "required", connection: "cert" }, replyReview: "external" });
  });

  it("back to never drops the field, and an empty object drops emailControl", () => {
    const onChange = vi.fn();
    renderWithProviders(<Harness initial={{ replyReview: "always" }} onChange={onChange} />);

    fireEvent.change(screen.getByTestId("reply-review-mode"), { target: { value: "never" } });

    expect(onChange).toHaveBeenLastCalledWith(undefined);
  });

  it("adds a valid internal domain, refuses an invalid one and removes one", () => {
    const onChange = vi.fn();
    renderWithProviders(<Harness initial={{ replyReview: "external" }} onChange={onChange} />);
    const input = screen.getByTestId("internal-domain-input");

    fireEvent.change(input, { target: { value: "@contoso" } });
    fireEvent.keyDown(input, { key: "Enter" });
    expect(screen.getByTestId("internal-domain-error")).toBeInTheDocument();
    expect(onChange).not.toHaveBeenCalled();

    fireEvent.change(input, { target: { value: "*.Contoso.com" } });
    fireEvent.keyDown(input, { key: "Enter" });
    expect(onChange).toHaveBeenLastCalledWith({ replyReview: "external", internalDomains: ["*.contoso.com"] });

    fireEvent.click(screen.getByRole("button", { name: /\*\.contoso\.com/ }));
    expect(onChange).toHaveBeenLastCalledWith({ replyReview: "external" });
  });

  it("trigger mode offers no review, only says why", () => {
    renderWithProviders(<Harness initial={undefined} conversation={false} onChange={vi.fn()} />);

    expect(screen.queryByTestId("reply-review-mode")).not.toBeInTheDocument();
    expect(screen.getByTestId("reply-review-trigger-note")).toBeInTheDocument();
  });

  it("matches the backend's domain patterns", () => {
    expect(isInternalDomainPattern("contoso.com")).toBe(true);
    expect(isInternalDomainPattern("*.contoso.co.uk")).toBe(true);
    for (const bad of ["contoso", "@contoso.com", "*contoso.com", "a@contoso.com", "contoso..com", "*.*.com"]) {
      expect(isInternalDomainPattern(bad)).toBe(false);
    }
  });
});
