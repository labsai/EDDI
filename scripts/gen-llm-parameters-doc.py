#!/usr/bin/env python3
"""Generate docs/llm-parameters.md from src/main/resources/llm/model-parameters.json.

The catalog is the single source of truth for which parameters each LLM provider
accepts and which model versions reject them; this page is its human-readable
rendering, so it is generated, never hand-edited:

    python scripts/gen-llm-parameters-doc.py

LlmParametersDocumentationTest fails when a provider or a parameter in the
catalog is missing from the page.
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CATALOG = ROOT / "src/main/resources/llm/model-parameters.json"
OUT = ROOT / "docs/llm-parameters.md"

GROUPS = [
    ("model", "Model"),
    ("sampling", "Sampling"),
    ("reasoning", "Reasoning and thinking"),
    ("output", "Output"),
    ("tools", "Tools"),
    ("caching", "Caching"),
    ("safety", "Safety"),
    ("connection", "Connection"),
    ("advanced", "Advanced"),
]

HEADER = """# LLM Parameters Reference

> Generated from [`model-parameters.json`](https://github.com/labsai/EDDI/blob/main/src/main/resources/llm/model-parameters.json)
> by `python scripts/gen-llm-parameters-doc.py`. Do not edit by hand.

Every LLM task takes a flat `parameters` map of **strings**. This page lists, per provider, every
parameter EDDI reads, what it does, and which model versions reject it or ignore it. The Manager shows
the same information as typed controls under **Model settings** in the LLM task editor.

```json
{
  "type": "openai",
  "parameters": {
    "modelName": "gpt-5.1",
    "apiKey": "${vault:openai-key}",
    "reasoningEffort": "low",
    "maxCompletionTokens": "4096",
    "stopSequences": "[\\"END\\"]",
    "customParameters": "{\\"web_search_options\\": {}}"
  }
}
```

## Value formats

| Type | Written as |
| --- | --- |
| `int`, `double` | `"4096"`, `"0.7"` |
| `boolean` | `"true"` / `"false"` |
| `enum` | one of the listed values (case-insensitive) |
| `stringList` | a JSON array `["END","\\n\\n"]`, or a comma list `END,STOP` (use the array when an item contains a comma) |
| `json` | a JSON object, e.g. `{"X-Team": "search"}` |

A value that cannot be parsed is ignored with a warning in the log (naming the parameter, never its
value), and the provider default stays in place.

## Model-aware behaviour

Providers change what a model accepts from one generation to the next. Reasoning models reject
`temperature`; newer Claude models reject non-default sampling parameters; some Gemini models ignore
them. EDDI knows this per model id:

- **Rejected**: the provider would answer with an HTTP error. EDDI removes the parameter before the
  request is built and logs one warning naming the parameter, the model and the reason. Set
  `eddi.llm.parameter-catalog.drop-rejected=false` to keep sending it and only warn.
  Where the model renames the parameter (`maxTokens` became `maxCompletionTokens` on OpenAI and Azure
  reasoning models), the value is carried over to the new name unless that is configured too, so an
  output cap is never silently lost.
- **No effect**: harmless but pointless. EDDI keeps it and logs one warning.
- Unknown provider or model id: nothing is restricted (fail open).

The model id is read from the model parameter of the provider (`modelName`, `model`, `modelId` or
`deploymentName`). On Azure OpenAI the deployment name says nothing about the underlying model, so set
`baseModel` (never sent to Azure) to get the same checks.

Anything not listed for a provider can still be sent with `customParameters` (extra top-level body
fields) and `customHeaders` on the providers that offer them.

## Limits worth knowing

- **Mistral**: `reasoning_effort`, `service_tier`, `top_k`, `min_tokens` and `prompt_mode` have no
  langchain4j setter, so they cannot be set from EDDI.
- **Ollama**: `think` is `true`/`false` only; level-based thinking (`low`/`medium`/`high`) is not modelled by langchain4j.
- **Hugging Face**: `baseUrl` is the service root, without `models/`.
- **Jlama**: the worker thread count is process-wide in the library and is not exposed.
- Rules are written only where an official vendor source states the behaviour; where sources conflict or
  say nothing, the parameter simply applies.
"""


def esc(text):
    return str(text).replace("|", "\\|").replace("\n", " ")


def resolved_params(catalog, provider_id):
    provider = catalog["providers"][provider_id]
    parent = provider.get("inherits")
    params = {}
    if parent:
        for p in catalog["providers"][parent]["params"]:
            inherited = dict(p)
            inherited["rules"] = []
            params[p["key"]] = inherited
    for p in provider["params"]:
        params[p["key"]] = p
    return list(params.values())


def constraint(p):
    bits = []
    if p.get("enum"):
        bits.append(" / ".join(f"`{v}`" for v in p["enum"]))
    if p.get("min") is not None or p.get("max") is not None:
        lo = p.get("min", "")
        hi = p.get("max", "")
        bits.append(f"{lo}–{hi}")
    if p.get("default") is not None:
        bits.append(f"default `{p['default']}`")
    return "; ".join(bits)


def render(catalog):
    out = [HEADER]
    for provider_id, provider in catalog["providers"].items():
        params = resolved_params(catalog, provider_id)
        out.append(f"\n## {provider['displayName']} (`{provider_id}`)\n")
        if provider.get("inherits"):
            out.append(
                f"OpenAI-compatible: accepts everything the [OpenAI](#openai-openai) builder does, "
                f"plus the vendor fields below. Model-version rules listed here are specific to this provider.\n"
            )
        for group_id, group_label in GROUPS:
            rows = [p for p in params if p.get("group") == group_id]
            if not rows:
                continue
            out.append(f"\n### {group_label}\n")
            out.append("| Parameter | Type | Values | Description |")
            out.append("| --- | --- | --- | --- |")
            for p in rows:
                key = f"`{p['key']}`"
                if p.get("wire"):
                    key += f" → `{p['wire']}`"
                if p.get("header"):
                    key += f" → header `{p['header']}`"
                out.append(f"| {key} | {p['type']} | {esc(constraint(p))} | {esc(p['description'])} |")
            rule_lines = []
            for p in rows:
                for rule in p.get("rules", []):
                    label = "Rejected" if rule["effect"] == "rejected" else "No effect"
                    carry = f" The value is sent as `{rule['migrateTo']}` instead." if rule.get("migrateTo") else ""
                    rule_lines.append(f"- `{p['key']}` — **{label}** on models matching `{rule['models']}`: {rule['note']}{carry}")
            if rule_lines:
                out.append("\nModel-specific behaviour:\n")
                out.extend(rule_lines)
    return "\n".join(out) + "\n"


def main():
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    OUT.write_text(render(catalog), encoding="utf-8")
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
