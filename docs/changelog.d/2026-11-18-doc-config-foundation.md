## ✨ feat(llm): the `documents` configuration block — model, validation, effective values (2026-11-18)

**Repo:** EDDI (`feat/doc-config-foundation`) — work package 0.1 of the PDF documents implementation plan (PDF structure, chapter splitting, agent-to-agent file sharing, a personal and team document library, large documents; the plan and its design are not yet in the repository)

No user-visible behaviour. The foundation every later documents package (handover, units, slices, library, large documents) makes keys live on.

### What changed and why

- **`LlmConfiguration.Task.documents`** (`DocumentsConfig`): the whole agent-level JSON shape of the design (§14), every field a nullable boxed type or nested object with no initializer, `NON_NULL` throughout. "Unset" stays distinguishable from "set to the default", so an operator default can apply and an untouched configuration saves byte-identical. The whole shape exists now so that a client sending a key before it ships gets the "not available" refusal, not the strict REST boundary's unknown-key 400. `bridge` has no fields yet (the design names only the block); the block itself is the key.
- **`DocumentsConfigValidator`**, called from `LlmStore` beside the other task validators, so create, update, JSON patch, import, setup and upgrade are all covered. Three rules on every key the designer set: a key not in `LIVE_KEYS` is refused ("… is not available in this version"); an unknown enum name is refused; a number above its operator bound, or negative, is refused naming field, value and bound. `LIVE_KEYS` is empty: nothing is available yet. Value-level entries (`structure=ON_DEMAND`) make one value of an enum-like key available.
- **`EffectiveDocumentsConfig.resolve(designer, settings)`**: designer value → `eddi.documents.defaults.<path>` → built-in default, then clamped to the bound. A key that is not live resolves to its built-in value, whatever was stored or configured. Never throws — not on a null list element, an unparsable operator default, nor an operator lookup that fails: everything ignored or clamped is reported in `clampWarnings()`. `LlmTask.configure()` resolves every task, with or without a block (so an ignored operator default is reported too), and logs each warning at WARN with the task id, so a stored config carrying an unavailable key (written directly to the database, or saved by a newer EDDI) still deploys. Every save path goes through the store: import, setup and upgrade call `create`/`updateLlm`.
- **`DocumentBounds`** (static, read through `ConfigProvider` because the store validators are static) and **`DocumentSettings`** (`@ApplicationScoped`, the operator-property holder for runtime code). Both are mechanism only; later packages append their properties in delimited blocks. A bound property that is not a non-negative number is skipped with a WARN, and the validator reads bounds only for a task that has a block, so a mistyped bound can never break every LLM save.

### Design decisions

- **Refuse at save, clamp and warn at deploy.** A bound tightened by an operator never un-deploys a running agent and never makes a stored configuration unloadable; only the next save of an over-bound value fails.
- **Enum names are validated against the names the design states.** Where the design names only some values (`unitForm`: `AUTO`, `BLOCKS`; `images.mode`: `EMBEDDED`; `embeddedFiles.extract`: `NONE`; `library.promote`: `TOOL`), only those are known; the package that makes a key live adds the rest. `large.onReady: CONTINUE` is not a value.
- **Tests read the design, not the code.** The design's JSON and defaults table are written out literally (`DocumentsDesignJson`), so a key missing from the class or a drifting default fails a test instead of being agreed with.

### Docs

- [LLM configuration](../langchain.md#documents-documents): the block, the resolution rules, the full shape, and an "available since" table later packages extend.
- [Configuration reference](../configuration-reference.md#documents): the `eddi.documents.defaults.<path>` convention.

### What's next

WP-1.1 (`AttachmentShareService`), WP-2a.1 (grants table) and WP-2a.4 (manifest extractor) are independent of this; WP-1.2 (`converseWithAgent` attachments) needs this and 1.1 merged.
