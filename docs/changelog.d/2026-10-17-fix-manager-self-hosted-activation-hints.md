## 🩹 fix(manager): self-hosted provider hints on the operator activation screen (2026-10-17)

**Repo:** EDDI (`feat/llm-openai-compatible-provider`)

Browser-checked the self-hosted provider in the agent wizard and operator activation. The wizard explained the required model id and Base URL; the operator activation screen showed both fields bare. It now shows the same hints (reusing the wizard's strings) and links them to the inputs with `aria-describedby`. Files: [`operator-activation.tsx`](../../ui/manager/src/components/operator/operator-activation.tsx).
