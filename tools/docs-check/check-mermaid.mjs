/**
 * Parses every ```mermaid block in the Markdown files of a folder with Mermaid's own parser, the same
 * library GitHub uses to render them. Exits 1 and names the file, block and line on any parse error.
 *
 *   node check-mermaid.mjs ../../docs
 */
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

import { JSDOM } from 'jsdom';

const dom = new JSDOM('<!doctype html><html><body></body></html>');
globalThis.window = dom.window;
globalThis.document = dom.window.document;
globalThis.DOMParser = dom.window.DOMParser;
globalThis.Element = dom.window.Element;
const { default: mermaid } = await import('mermaid');
mermaid.initialize({ startOnLoad: false });

const dir = process.argv[2] ?? '../../docs';
let total = 0;
let failed = 0;
for (const file of readdirSync(dir).filter((f) => f.endsWith('.md')).sort()) {
  const text = readFileSync(join(dir, file), 'utf8');
  for (const match of text.matchAll(/```mermaid\n([\s\S]*?)```/g)) {
    total++;
    const line = text.slice(0, match.index).split('\n').length;
    try {
      await mermaid.parse(match[1]);
    } catch (e) {
      failed++;
      console.error(`✗ ${file}:${line} (${match[1].split('\n')[0]})\n  ${String(e?.message ?? e).split('\n').slice(0, 4).join('\n  ')}`);
    }
  }
}
console.log(`${total - failed}/${total} Mermaid diagrams parse.`);
process.exit(failed ? 1 : 0);
