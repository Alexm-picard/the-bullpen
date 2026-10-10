/**
 * Shared test helper: the VISIBLE TEXT of server-rendered markup.
 *
 * The visible text of a rendered fragment - what a reader would actually see.
 *
 * Assertions on this page MUST run against this rather than raw markup: Mantine injects a
 * stylesheet, and the live pitch board renders the same pitch's count in its own `Cnt` column, so
 * a bare `toContain("2-1")` matches the BOARD and passes even when the Count stat correctly
 * em-dashes. That is exactly how the first version of the past-tense assertion could not fail.
 *
 * Scanned rather than regex-stripped, deliberately. The obvious
 * `.replace(/<style[\s\S]*?<\/style>/g, "").replace(/<[^>]+>/g, " ")` chain is shaped like an
 * HTML sanitizer, and CodeQL flags it as one (js/incomplete-multi-character-sanitization, high) -
 * correctly in general, even though nothing untrusted reaches this helper and its output is only
 * ever asserted on. Rewriting removes the finding at its root instead of dismissing it. The scan
 * is also simply more accurate: it skips a <style> element's CONTENT rather than hoping a lazy
 * quantifier lines up, and drops attributes wholesale so no inline style= value can leak a token
 * into an assertion.
 *
 * A tag boundary emits one space, so adjacent elements stay separate tokens and label/value
 * assertions like /Count\s+—/ mean what they look like. Entity references are left ENCODED
 * (&#x27; stays &#x27;), which is why assertions here compare rendered words, not punctuation.
 */
export function visibleText(html: string): string {
  const out: string[] = [];
  let i = 0;
  while (i < html.length) {
    const lt = html.indexOf("<", i);
    if (lt === -1) {
      out.push(html.slice(i));
      break;
    }
    out.push(html.slice(i, lt));
    const gt = html.indexOf(">", lt);
    if (gt === -1) break; // truncated final tag: nothing visible can follow it
    const name = html
      .slice(lt + 1, gt)
      .trim()
      .toLowerCase()
      .split(/[\s/]/)[0];
    if (name === "style" || name === "script") {
      // Skip the whole ELEMENT: its content is CSS/JS, never page text.
      const close = html.toLowerCase().indexOf(`</${name}`, gt);
      const closeEnd = close === -1 ? -1 : html.indexOf(">", close);
      i = closeEnd === -1 ? html.length : closeEnd + 1;
    } else {
      i = gt + 1;
    }
    out.push(" ");
  }
  return out.join("");
}
