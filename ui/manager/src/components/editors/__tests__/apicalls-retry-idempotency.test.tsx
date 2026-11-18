import { describe, it, expect, vi } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import { renderWithProviders } from "@eddi/app-kit/test/test-utils";
import { RetryApiCallEditor } from "@eddi/app-kit/components/editors/apicalls-editor";
import { isNonIdempotentMethod } from "@eddi/app-kit/lib/http-methods";

describe("isNonIdempotentMethod", () => {
  it("flags POST and PATCH, not the safe or idempotent methods", () => {
    expect(isNonIdempotentMethod("POST")).toBe(true);
    expect(isNonIdempotentMethod("patch")).toBe(true);
    for (const m of ["GET", "PUT", "DELETE", "HEAD", undefined]) expect(isNonIdempotentMethod(m)).toBe(false);
  });
});

describe("RetryApiCallEditor — default policy", () => {
  it("defaults to retry on 502/503 three times for an idempotent method", () => {
    const onChange = vi.fn();
    renderWithProviders(<RetryApiCallEditor retry={undefined} onChange={onChange} method="GET" />);
    fireEvent.click(screen.getByTestId("add-retry-instruction"));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ maxRetries: 3, retryOnHttpCodes: [502, 503] }),
    );
  });

  it("defaults to no retry for POST", () => {
    const onChange = vi.fn();
    renderWithProviders(<RetryApiCallEditor retry={undefined} onChange={onChange} method="POST" />);
    fireEvent.click(screen.getByTestId("add-retry-instruction"));
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ maxRetries: 0, retryOnHttpCodes: [] }));
  });

  it("warns about idempotency once a POST policy actually retries", () => {
    const { rerender } = renderWithProviders(
      <RetryApiCallEditor
        retry={{ maxRetries: 0, exponentialBackoffDelayInMillis: 1000, retryOnHttpCodes: [], responseValuePathMatchers: [] }}
        onChange={vi.fn()}
        method="POST"
      />,
    );
    expect(screen.getByTestId("retry-idempotency-warning")).not.toHaveAttribute("role");
    rerender(
      <RetryApiCallEditor
        retry={{ maxRetries: 3, exponentialBackoffDelayInMillis: 1000, retryOnHttpCodes: [503], responseValuePathMatchers: [] }}
        onChange={vi.fn()}
        method="POST"
      />,
    );
    expect(screen.getByTestId("retry-idempotency-warning")).toHaveAttribute("role", "alert");
  });

  it("shows no idempotency note for GET", () => {
    renderWithProviders(
      <RetryApiCallEditor
        retry={{ maxRetries: 3, exponentialBackoffDelayInMillis: 1000, retryOnHttpCodes: [503], responseValuePathMatchers: [] }}
        onChange={vi.fn()}
        method="GET"
      />,
    );
    expect(screen.queryByTestId("retry-idempotency-warning")).not.toBeInTheDocument();
  });
});
