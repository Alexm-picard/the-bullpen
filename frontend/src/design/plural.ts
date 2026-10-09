/**
 * Count-noun pluralization through Intl so "1 models" and "1 balls in play"
 * cannot ship (break-ui catalog: hand-rolled plurals). en-US is the product's
 * locale everywhere else (Intl.DateTimeFormat sites).
 */
const rules = new Intl.PluralRules("en-US");

export function plural(n: number, one: string, other = `${one}s`): string {
  return rules.select(n) === "one" ? one : other;
}
