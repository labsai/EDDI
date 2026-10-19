/**
 * A line diff between the operator's current default instructions and the text
 * stored in its configuration, for the review shown before an upgrade or a
 * reconfigure installs the stored text on a new agent.
 *
 * The stored text lives in `platform.operator`, which an editor can write. The
 * admin who clicks Upgrade is the one who approves it, so they get to see what
 * differs rather than a bare "keep the current text" radio button.
 *
 * Plain longest-common-subsequence over lines: instruction bodies are a few
 * dozen lines, so the O(n*m) table is nothing, and there is no dependency to
 * trust with the one screen whose job is to distrust its input.
 */

export interface DiffLine {
  kind: "same" | "added" | "removed";
  text: string;
}

/** Above this many lines on either side the table is skipped and everything is shown as changed. */
const MAX_LINES = 2000;

export function diffLines(base: string, current: string): DiffLine[] {
  const a = base.replace(/\r\n/g, "\n").split("\n");
  const b = current.replace(/\r\n/g, "\n").split("\n");

  if (a.length > MAX_LINES || b.length > MAX_LINES) {
    return [
      ...a.map((text): DiffLine => ({ kind: "removed", text })),
      ...b.map((text): DiffLine => ({ kind: "added", text })),
    ];
  }

  // lcs[i][j] = length of the LCS of a[i..] and b[j..]. Rows are allocated for
  // every i in 0..a.length, so the lookups below never miss.
  const lcs: number[][] = Array.from({ length: a.length + 1 }, () => new Array<number>(b.length + 1).fill(0));
  const cell = (i: number, j: number): number => lcs[i]?.[j] ?? 0;
  for (let i = a.length - 1; i >= 0; i--) {
    const row = lcs[i] as number[];
    for (let j = b.length - 1; j >= 0; j--) {
      row[j] = a[i] === b[j] ? cell(i + 1, j + 1) + 1 : Math.max(cell(i + 1, j), cell(i, j + 1));
    }
  }

  const out: DiffLine[] = [];
  let i = 0;
  let j = 0;
  while (i < a.length && j < b.length) {
    const left = a[i] as string;
    const right = b[j] as string;
    if (left === right) {
      out.push({ kind: "same", text: left });
      i++;
      j++;
    } else if (cell(i + 1, j) >= cell(i, j + 1)) {
      out.push({ kind: "removed", text: left });
      i++;
    } else {
      out.push({ kind: "added", text: right });
      j++;
    }
  }
  for (; i < a.length; i++) out.push({ kind: "removed", text: a[i] as string });
  for (; j < b.length; j++) out.push({ kind: "added", text: b[j] as string });
  return out;
}

/** How many lines of `current` are not in `base`, and how many of `base` are gone. */
export function summarizeDiff(lines: DiffLine[]): { added: number; removed: number } {
  let added = 0;
  let removed = 0;
  for (const line of lines) {
    if (line.kind === "added") added++;
    else if (line.kind === "removed") removed++;
  }
  return { added, removed };
}
