/**
 * Wildcard match where `*` matches any run of characters (newlines included) and every other character is
 * literal - the semantics of the backend's `ToolApprovalPatterns`.
 *
 * Iterative with a single backtrack point (the last `*`), so it cannot go exponential on a pattern with many
 * wildcards, which a regular expression built from the pattern does: `"*".repeat(20) + "x*"` took ~29 s to
 * reject a short address. Worst case O(pattern x text), both short here.
 */
export function globMatches(glob: string, text: string): boolean {
  let g = 0;
  let t = 0;
  let starAt = -1;
  let resumeAt = 0;
  while (t < text.length) {
    if (g < glob.length && glob[g] === "*") {
      starAt = g++;
      resumeAt = t;
    } else if (g < glob.length && glob[g] === text[t]) {
      g++;
      t++;
    } else if (starAt >= 0) {
      g = starAt + 1;
      t = ++resumeAt;
    } else {
      return false;
    }
  }
  while (g < glob.length && glob[g] === "*") g++;
  return g === glob.length;
}
