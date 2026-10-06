## Qute migration parses what it converts, and converts nested conditionals (2026-10-06)

- **Repo / branch:** EDDI, `fix/qute-migration-validate-output`.
- **Problem:** on a production 5.x deployment the Thymeleaf-to-Qute migration turned a nested
  conditional (`[[${a > 0 ? 'x ' + a + (b > 0 ? 'y' : 'z') : 'w'}]]`) into text Qute cannot parse,
  counted the document as migrated, and logged "migration complete". Every render of it then
  failed with `Parser error: invalid identifier found`.
- **What changed:**
  - `TemplateSyntaxMigrator.quteParseError` parses a converted template with a plain Qute engine
    (parse only, nothing rendered; namespaces such as `vars:`, `json:`, `vault:` are resolved at
    render time and do not fail a parse). `V6QuteMigration` runs it on every converted string. A
    template that does not parse keeps its original, makes the document unconvertible and takes
    the existing path: one ERROR, the boot summary, the migration not marked complete, the
    document not counted as migrated. The archive import runs the same check and imports the
    original with a WARN instead.
  - New `OgnlTernaryConverter`, a small recursive-descent parser: `cond ? a : b`, nested to any
    depth, with `'lit' + path + 'lit'` concatenation in the branches, becomes
    `{#if cond}a{#else}b{/if}`. Conditions are paths, string and number comparisons, `&&`/`||`/`!`
    (and `and`/`or`/`not`) and parentheses. A comparison against a number is guarded
    (`x && x > 0`), because Qute throws when it compares a missing value.
  - `migrateCloseTags` now pops its stack on a Qute `{/if}` / `{/for}`, so a converted conditional
    inside a Thymeleaf `[# th:if]…[/]` element does not steal the `[/]`.
- **Design decisions:** the converter answers "not sure" rather than guessing, and the caller then
  falls back to the previous conversion, whose result is judged by the parse check. Comparisons
  such as `x >= 0` or `x < 5` are not converted by it, because the zero-is-false guard would change
  the answer for `x == 0`; they fall back and, if Qute rejects the result, are reported. Plain
  Elvis (`a ?: b`) and safe navigation (`a?.b`) are not conditionals and are unchanged.
- **Follow-ups:** none required. A stray string in an imported archive that is not valid Qute now
  also blocks the conversion of the resource it sits in (the original is imported, with a WARN).
