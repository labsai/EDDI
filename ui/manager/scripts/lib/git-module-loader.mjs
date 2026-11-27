/**
 * Module-loader hooks that serve a historical module graph straight out of git,
 * for scripts/gen-operator-prompt-lineage.mjs.
 *
 * A URL `gitsrc:///<sha>/<repo path>` loads `git show <sha>:<repo path>`. Inside
 * such a module, relative imports and the Manager's two aliases (`@/` →
 * ui/manager/src, `@eddi/app-kit/` → ui/packages/app-kit/src) resolve to the same
 * commit, trying the extensions the bundler would. TypeScript is stripped by
 * Node itself; JSON becomes a default export. Anything else (an npm package) is
 * not resolvable at a past commit and fails that commit's render, which the
 * generator reports and skips.
 */
import { execFileSync } from "node:child_process";
import { stripTypeScriptTypes } from "node:module";
import path from "node:path";

let repoRoot = process.cwd();

export function initialize(data) {
  if (data?.repoRoot) repoRoot = data.repoRoot;
}

const PREFIX = "gitsrc:///";
const EXTENSIONS = ["", ".ts", ".tsx", ".mjs", ".js", ".json", "/index.ts", "/index.js"];
const ALIASES = [
  ["@/", "ui/manager/src/"],
  ["@eddi/app-kit/", "ui/packages/app-kit/src/"],
];

function parse(url) {
  const rest = url.slice(PREFIX.length);
  const slash = rest.indexOf("/");
  return { sha: rest.slice(0, slash), file: rest.slice(slash + 1) };
}

function exists(sha, file) {
  try {
    execFileSync("git", ["cat-file", "-e", `${sha}:${file}`], { cwd: repoRoot, stdio: "ignore" });
    return true;
  } catch {
    return false;
  }
}

export async function resolve(specifier, context, nextResolve) {
  if (specifier.startsWith(PREFIX)) return { url: specifier, shortCircuit: true };
  if (!context.parentURL?.startsWith(PREFIX)) return nextResolve(specifier, context);
  const { sha, file } = parse(context.parentURL);
  let base = null;
  if (specifier.startsWith(".")) base = path.posix.normalize(path.posix.join(path.posix.dirname(file), specifier));
  for (const [alias, target] of ALIASES) {
    if (specifier.startsWith(alias)) base = target + specifier.slice(alias.length);
  }
  if (base === null) {
    throw new Error(`cannot resolve "${specifier}" from ${file} at ${sha.slice(0, 10)}`);
  }
  for (const ext of EXTENSIONS) {
    if (exists(sha, base + ext)) return { url: `${PREFIX}${sha}/${base}${ext}`, shortCircuit: true };
  }
  throw new Error(`"${specifier}" (${base}) does not exist at ${sha.slice(0, 10)}`);
}

export async function load(url, context, nextLoad) {
  if (!url.startsWith(PREFIX)) return nextLoad(url, context);
  const { sha, file } = parse(url);
  const text = execFileSync("git", ["show", `${sha}:${file}`], { cwd: repoRoot, encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
  let source;
  if (file.endsWith(".json")) {
    source = `export default ${text};`;
  } else if (file.endsWith(".ts") || file.endsWith(".tsx")) {
    source = stripTypeScriptTypes(text);
  } else {
    source = text;
  }
  // Vite's import.meta.env does not exist under Node.
  source = `import.meta.env ??= { DEV: false, PROD: true, MODE: "production" };\n${source}`;
  return { format: "module", source, shortCircuit: true };
}
