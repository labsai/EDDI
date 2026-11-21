## fix(nlp): a chat message with stray parentheses no longer fails the turn (2026-11-06)

**Repo:** EDDI (`fix/user-input-expression-depth`)

A production deployment on 6.6.0 failed three turns in five minutes when users pasted long text containing list
markers and unbalanced parentheses (`a)`, `(It`, `3))`). The log showed `Error while parsing Expression` repeated, then
`ExpressionLimitExceededException: Expressions nest 33 levels deep; at most 32 are accepted`, and the user received
the generic fallback answer.

**Root cause:** every token no dictionary knew became `unknown(<token>)` (`Unknown`), and `InputParserTask` stores the
expressions of a turn as one string that rule matching, the property setter and the append merge parse again. The
token text went into that string verbatim, so each `(` in the message became an opening bracket in expression syntax.
Opening brackets from different tokens add up across the whole message; past 32 the depth bound added in 6.6 refused
the string and the exception failed the lifecycle. Before the bound the same input produced silently wrong nesting.
`main` still had it.

**Fix:** `Unknown` now builds `unknown(<token>)` from the token without `(`, `)` and `,`, the three characters the
notation gives meaning to; a token that is only such characters becomes a bare `unknown`. The word itself (`getValue()`,
match details, the conversation input) is untouched, and the other dictionaries that build expressions from runtime
text only accept digits, e-mail addresses or epoch times, which cannot carry those characters. The malformed-expression
log in `ExpressionProvider` is a WARN instead of an ERROR.

**Decision:** `eddi.expressions.max-depth` and `eddi.expressions.max-length` are unchanged and still refuse
configuration-authored and client-supplied (`context` type `expressions`) nesting; user chat text is data and is
sanitised where it becomes an expression, not by loosening the guard.

**Tests:** `UserInputExpressionRoundTripTest` runs prose with many unbalanced parentheses through the parser, the
stored expression string and the re-parse (failed with the 33-level error before the fix), checks that dictionary words
still match, and that authored deep nesting is still refused.
