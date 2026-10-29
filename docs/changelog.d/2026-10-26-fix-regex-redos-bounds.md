## 🐛 fix(security): bound the regex work of seven matchers (CWE-1333) (2026-10-26)

**Repo:** EDDI (`fix/regex-redos-bounds`)

### What changed and why

A repository security scan flagged seven places where a pattern from configuration, or text from a user, could make
`java.util.regex` backtrack super-linearly. All seven were reproduced; each is now bounded, and where a regex
was kept its meaning is unchanged — each fix ships with a test comparing old and new over a dense random or
exhaustive corpus, plus a timing-bounded pathological input that hangs the old code.

- **`ToolApprovalPatterns.compile`** (HITL tool-approval globs): one greedy `.*` per star made `a*a*…*z`
  exponential in the star count, at save-time validation and on every gated tool call. Middle segments are now
  matched at their earliest occurrence inside atomic groups (`(?>.*?B)`), which loses no match, so matching
  costs pattern length × input length. The `Pattern` return type stays, so the gate and the rules are untouched.
- **`UrlPattern`** (crawl `excludePatterns`): the glob is no longer translated to a regex; a dynamic-programming
  pass over the tokens costs pattern length × path length. `**` now also crosses a decoded line terminator — the
  regex's `.` did not, so `/private/**` let `/private/a%0Ab` through.
- **`BoundedRegex`** (agent test-suite regexes): its deadline only fires on `charAt`, and a counted repetition of
  something that matches empty reads nothing — `(?:(?:){99999}){99999}` spun for 25 s on a three-character
  text. Counted minimums over groups, anchors and back-references may now force at most 1,000 iterations in
  total (nested counts multiply), and `(?x)` is refused. Checked when a suite is saved
  (`AgentTestSuiteValidator`) and again before every match, for suites saved earlier. What remains is documented
  in the class: a pattern within the cap that reads nothing still costs up to 1,000 steps per start position,
  unchecked by the deadline.
- **`TemplateSyntaxMigrator`** (Thymeleaf→Qute import): the three `#strings` patterns are possessive where the
  next token cannot be what the quantifier consumes, and the two-argument form checks for its `)` with a
  lookahead first; a long whitespace run in an unterminated call was quadratic.
- **`SecretRedactionFilter`** unquoted-value rules: the optional `${vault:…}` prefix searched for its closing
  brace afresh at every key, so repeated `token:${vault:` without a `}` was quadratic in the message. The key is
  still found by regex; the reference and value are now found by a forward-only scan whose brace search is
  reused across keys. Whole-reference exemption and reference-plus-tail redaction are unchanged (no new bypass).
- **`PunctuationNormalizerProvider`**: the punctuation setting is spliced into `[…]` and could close that class
  (`!]|(a+)+$|[!`). It is used as a class body only when its brackets balance and it has no `\Q…\E`; otherwise
  its characters are taken literally. The provider also no longer keeps the last parser's setting in a field of
  the shared singleton, which made one parser's custom punctuation the default of every later one.
- **`PromptInjectionGuardrail`**: whitespace runs in the built-in patterns are possessive; `ignore` or `<`
  followed by a long whitespace run was quadratic.

**Docs:** [`agent-testing.md`](../agent-testing.md) — the repetition cap.

**Files:** `ToolApprovalPatterns`, `UrlPattern`, `BoundedRegex`, `AgentTestSuiteValidator`,
`TemplateSyntaxMigrator`, `SecretRedactionFilter`, `PunctuationNormalizerProvider`, `PromptInjectionGuardrail`
and their tests (new: `BoundedRegexTest`, `SecretRedactionFilterUnquotedValuesTest`).
