#!/usr/bin/env node
/**
 * Fail the mutation job if it tested nothing.
 *
 * Stryker scores an empty run `NaN` (`totalValid > 0 ? … : DEFAULT_SCORE` in
 * mutation-testing-metrics), and the break check is `mutationScore < break`.
 * `NaN < 82` is `false`, so a run that mutated zero files exits 0 and reports
 * success. The only warning is a WARN-level "Glob pattern did not result in any
 * files", which nobody reads in a green build.
 *
 * That is one rename away from real: move a guarded file out of
 * `src/lib/operator/`, or mistype a glob in `stryker.config.json`, and the gate
 * keeps passing while guarding nothing. A gate whose failure mode is silent
 * success is the exact thing this whole job exists to argue against.
 *
 * Counts VALID mutants — everything except `Ignored` — because that is what the
 * score is computed over. `ignoreStatic` marks module-level mutants `Ignored`
 * and they never run; 70 of the 1,492 in a normal run are in that state. A
 * count that included them would report a healthy number for a run in which
 * every single mutant was skipped and the score was still NaN.
 *
 * Deliberately a floor of one rather than a fixed expected count: narrowing the
 * scope on purpose is legitimate and should not need this file edited in the
 * same commit. The counts are printed so a large unintended drop is visible in
 * the log even though it does not fail here.
 */

import { readFileSync } from "node:fs";

const REPORT = "reports/mutation/mutation.json";

let report;
try {
  report = JSON.parse(readFileSync(REPORT, "utf8"));
} catch (err) {
  console.error(`Could not read ${REPORT}: ${err.message}`);
  console.error("Stryker should have written it — is the `json` reporter still enabled?");
  process.exit(1);
}

const files = Object.entries(report.files ?? {});
const mutants = files.flatMap(([path, file]) => (file.mutants ?? []).map((m) => ({ ...m, path })));
const valid = mutants.filter((m) => m.status !== "Ignored");

if (valid.length === 0) {
  console.error(`Stryker tested 0 mutants, and scored that NaN rather than failing.`);
  console.error(`Nothing was verified. ${mutants.length} mutant(s) were generated and all`);
  console.error("were ignored, or none were generated at all — check the `mutate` globs in");
  console.error("stryker.config.json still match real files. A moved or renamed file, or a");
  console.error("scope in which every mutant is static, both look exactly like this.");
  process.exit(1);
}

const ignored = mutants.length - valid.length;
console.log(`Mutants tested: ${valid.length} across ${files.length} file(s) (${ignored} ignored).`);

const perFile = new Map();
for (const m of valid) perFile.set(m.path, (perFile.get(m.path) ?? 0) + 1);
for (const [path, n] of [...perFile].sort((a, b) => b[1] - a[1])) {
  console.log(`  ${String(n).padStart(5)}  ${path}`);
}
