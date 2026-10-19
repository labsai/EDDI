import { describe, it, expect } from "vitest";
import DOMPurify from "dompurify";
import { sanitizeAgentHtml } from "../markdown-safe";

function parse(html: string): HTMLElement {
  const root = document.createElement("div");
  root.innerHTML = sanitizeAgentHtml(html);
  return root;
}

describe("sanitizeAgentHtml", () => {
  it("strips class so Tailwind utilities cannot build a full-viewport overlay", () => {
    const out = sanitizeAgentHtml(
      '<div class="fixed inset-0 z-50 bg-background">Session expired - enter your password</div>'
    );
    expect(out).not.toMatch(/class=/i);
    expect(out).not.toMatch(/fixed|inset-0|z-50/);
    // The text survives; only the styling hook is gone.
    expect(out).toContain("Session expired");
  });

  it("still strips inline style", () => {
    expect(sanitizeAgentHtml('<p style="position:fixed;inset:0">x</p>')).not.toMatch(/style=/i);
  });

  it("drops svg with an <image href> (a render-time fetch)", () => {
    const out = sanitizeAgentHtml(
      '<svg width="1" height="1"><image href="https://attacker.example/pixel.png"/></svg>ok'
    );
    expect(out).not.toMatch(/<svg|<image|attacker\.example/i);
    expect(out).toContain("ok");
  });

  it.each([
    ["feImage", '<svg><filter><feImage href="https://attacker.example/p"/></filter></svg>'],
    ["use", '<svg><use href="https://attacker.example/x.svg#a"/></svg>'],
    ["math", '<math><mi xlink:href="https://attacker.example">x</mi></math>'],
    ["bare image", '<image href="https://attacker.example/p"/>'],
  ])("drops %s", (_name, html) => {
    const out = sanitizeAgentHtml(html);
    expect(out).not.toMatch(/<svg|<math|<image|<feimage|<use|attacker\.example/i);
  });

  it("forces target=_blank and rel=noopener noreferrer on every anchor", () => {
    const root = parse(
      '<a href="https://example.com">a</a><a href="/x" target="_self" rel="opener">b</a>'
    );
    const anchors = root.querySelectorAll("a");
    expect(anchors).toHaveLength(2);
    anchors.forEach((a) => {
      expect(a.getAttribute("target")).toBe("_blank");
      expect(a.getAttribute("rel")).toBe("noopener noreferrer");
    });
  });

  it("keeps rewriting <img> to a link that also opens safely", () => {
    const root = parse('<img src="https://attacker.example/p.png" alt="chart">');
    expect(root.querySelector("img")).toBeNull();
    const a = root.querySelector("a");
    expect(a?.getAttribute("href")).toBe("https://attacker.example/p.png");
    expect(a?.getAttribute("rel")).toBe("noopener noreferrer");
  });

  it("does not leave its link hook registered on the shared DOMPurify instance", () => {
    sanitizeAgentHtml('<a href="https://example.com">a</a>');
    const out = DOMPurify.sanitize('<a href="https://example.com">a</a>');
    expect(out).not.toContain("target=");
  });
});
