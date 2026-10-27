// Entry point that makes `node --test site/tests` work on Node 21+, where test-runner arguments are file
// patterns: the pattern `site/tests` names this directory, Node resolves a directory to its index.js, and this
// file loads every *.test.mjs next to it. (Node 20 scans the directory itself and skips this file, because
// index.js does not match the test-file name patterns.)
import { readdirSync } from 'node:fs';

const dir = new URL('./', import.meta.url);
for (const file of readdirSync(dir).filter((f) => f.endsWith('.test.mjs')).sort()) {
  await import(new URL(file, dir).href);
}
