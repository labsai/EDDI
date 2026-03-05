# Phase 0: Security Quick Wins — Walkthrough

**Branch**: `feature/version-6.0.0`
**Commit**: `71448a89` — `feat(security): restrict CORS origins and replace OGNL with PathNavigator`

---

## Changes Made

### 1. CORS Restriction

Added `quarkus.http.cors.origins` to [application.properties](file:///c:/dev/git/EDDI/src/main/resources/application.properties):

```diff
 quarkus.http.cors.enabled=true
+quarkus.http.cors.origins=http://localhost:3000,http://localhost:7070
 quarkus.http.cors.headers=accept, origin, authorization, content-type, x-requested-with
```

Previously: **no origins restriction** — any domain could make cross-origin requests.

### 2. PathNavigator (replaces OGNL)

| File                                                                                                                          | Change                                                               |
| ----------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| [PathNavigator.java](file:///c:/dev/git/EDDI/src/main/java/ai/labs/eddi/utils/PathNavigator.java)                             | **[NEW]** Safe path navigation utility (~220 lines)                  |
| [PathNavigatorTest.java](file:///c:/dev/git/EDDI/src/test/java/ai/labs/eddi/utils/PathNavigatorTest.java)                     | **[NEW]** 27 unit tests                                              |
| [MatchingUtilities.java](file:///c:/dev/git/EDDI/src/main/java/ai/labs/eddi/utils/MatchingUtilities.java)                     | Replaced `Ognl.getValue()` → `PathNavigator.getValue()`              |
| [PropertySetterTask.java](file:///c:/dev/git/EDDI/src/main/java/ai/labs/eddi/modules/properties/impl/PropertySetterTask.java) | Replaced `Ognl.getValue()` + `Ognl.setValue()` → `PathNavigator`     |
| [PrePostUtils.java](file:///c:/dev/git/EDDI/src/main/java/ai/labs/eddi/modules/httpcalls/impl/PrePostUtils.java)              | Replaced `Ognl.getValue()` → `PathNavigator.getValue()`              |
| [SizeMatcher.java](file:///c:/dev/git/EDDI/src/main/java/ai/labs/eddi/modules/behavior/impl/conditions/SizeMatcher.java)      | Replaced `Ognl.getValue()` → `PathNavigator.getValue()` + null check |

> [!NOTE]
> **Thymeleaf's internal OGNL is unaffected.** Template expressions like `${userInfo.chapterNumber > 0 ? '...' : ''}` continue working through `ITemplatingEngine`. Only the 5 explicit `Ognl.getValue()`/`Ognl.setValue()` calls in Java code were replaced.

---

## Test Results

```
Tests run: 499, Failures: 0, Errors: 0, Skipped: 4
```

All existing tests pass. 27 new PathNavigator tests cover real EDDI patterns:

- Simple dot-paths (`properties.botLocation`)
- Array indices (`httpCalls.currentWeather.weather[0].description`)
- Arithmetic (`properties.count+1`)
- String concatenation (`name+' '`)
- Negative index safety (`items[-1]` → null)

---

## What's Next

**Phase 1: Backend Foundation** — Extract `ConversationService` from `RestBotEngine` (5 SP)
