/* T1.7 — rich output under the one markdown policy: a fenced block names its
   language, and a ```mermaid block is drawn by Mermaid (strict, lazy) or stays
   the code block it is. Mermaid itself is replaced here: the test pins what
   the renderer asks of it and what it does with the answer. */

import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";

const renderMermaid = vi.hoisted(() => vi.fn<(source: string, dark: boolean) => Promise<string>>());
vi.mock("./rich-mermaid", () => ({ renderMermaid }));

import { codeLanguage, MarkdownContent } from "./Markdown";

afterEach(() => {
  renderMermaid.mockReset();
  document.documentElement.classList.remove("dark");
});

describe("code blocks", () => {
  it("name their language and keep their copy button", () => {
    render(<MarkdownContent enableCodeHighlight={false}>{"```python\nprint('hi')\n```"}</MarkdownContent>);
    expect(screen.getByTestId("code-language")).toHaveTextContent("python");
    expect(screen.getByTestId("copy-button")).toBeInTheDocument();
  });

  it("say nothing when the fence names no language", () => {
    render(<MarkdownContent enableCodeHighlight={false}>{"```\nplain\n```"}</MarkdownContent>);
    expect(screen.queryByTestId("code-language")).toBeNull();
  });

  it("read the language from the code element's class only", () => {
    expect(codeLanguage(<code className="hljs language-TypeScript" />)).toBe("typescript");
    expect(codeLanguage(<code className="hljs" />)).toBeNull();
    expect(codeLanguage("text")).toBeNull();
  });
});

describe("mermaid", () => {
  const diagram = "```mermaid\ngraph TD; A-->B\n```";

  it("draws a diagram from Mermaid's output once its source settles, in the page's theme", async () => {
    document.documentElement.classList.add("dark");
    renderMermaid.mockResolvedValue('<svg data-drawn="yes"><text>A</text></svg>');
    const { container } = render(<MarkdownContent enableCodeHighlight={false}>{diagram}</MarkdownContent>);
    // The code block first: nothing is drawn while the source may still grow.
    expect(screen.getByTestId("code-language")).toHaveTextContent("mermaid");
    expect(await screen.findByTestId("mermaid-diagram")).toBeInTheDocument();
    expect(renderMermaid).toHaveBeenCalledWith("graph TD; A-->B\n", true);
    expect(container.querySelector('svg[data-drawn="yes"]')).not.toBeNull();
    expect(screen.getByRole("img", { name: "Diagram" })).toBeInTheDocument();
  });

  it("stays the code block when the source does not parse", async () => {
    renderMermaid.mockRejectedValue(new Error("Parse error"));
    render(<MarkdownContent enableCodeHighlight={false}>{diagram}</MarkdownContent>);
    await waitFor(() => expect(renderMermaid).toHaveBeenCalled());
    expect(screen.queryByTestId("mermaid-diagram")).toBeNull();
    expect(screen.getByText(/graph TD/)).toBeInTheDocument();
  });

  it("is never asked for an ordinary code block", async () => {
    render(<MarkdownContent enableCodeHighlight={false}>{"```js\nlet a\n```"}</MarkdownContent>);
    await new Promise((r) => setTimeout(r, 450));
    expect(renderMermaid).not.toHaveBeenCalled();
  });
});
