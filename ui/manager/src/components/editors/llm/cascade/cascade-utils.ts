/** Parse a numeric input into a value, or `undefined` when blank/invalid. */
export function parseNum(v: string): number | undefined {
  if (v === "") return undefined;
  const n = parseFloat(v);
  return isNaN(n) ? undefined : n;
}

/**
 * First free `param{N}` key that does not collide with an existing parameter,
 * so adding a parameter after removing an earlier one never overwrites a value.
 */
export function nextParamKey(params: Record<string, string>): string {
  let n = 0;
  while (Object.prototype.hasOwnProperty.call(params, `param${n}`)) n++;
  return `param${n}`;
}
