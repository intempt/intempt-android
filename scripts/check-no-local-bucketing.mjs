#!/usr/bin/env node
/**
 * EXP-ASSIGN-001 / EXP-ASSIGN-005: bucket derivation is server-only.
 *
 * Cited "R36" until 2026-08-31; no such id exists in brain. The two ids above are the real ones,
 * in brain product/specs/experiences/experiences-spec.md.
 *
 * The platform decides which variant a person gets, by hashing (experienceId, identifier) and
 * taking the result modulo the bucket count. No SDK may do that arithmetic itself.
 *
 * The failure this prevents is silent and unreportable: two derivations that disagree serve the
 * same person a different variant depending on which channel they arrive through. Nothing in the
 * product surfaces it, because each side is internally consistent.
 *
 * This was true of all SDKs by accident before it was a rule. This guard makes it a fact.
 *
 * Zero dependencies, so it runs before install and on a machine that cannot build this SDK.
 *
 * An entry may be allowed — hashing has legitimate non-bucketing uses, idempotency keys and cache
 * keys among them — by listing it in the sidecar allowlist with a reason. An allowlist entry that
 * no longer matches anything is itself an error, so the file cannot silently rot.
 *
 * Two ways this guard could pass while checking nothing, both now closed: an allowlist that has
 * rotted (above), and a scan root that does not exist (below). The second one bit — GUARD_SRC
 * defaulted to 'src', which no Android project has, so the guard read zero files and said OK.
 */

import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, relative, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = process.env.GUARD_ROOT ?? join(here, '..');
// Defaults to this repo's real source root. It was 'src', which does not exist in an Android
// project, so a plain `node scripts/check-no-local-bucketing.mjs` scanned nothing and then
// blamed the allowlist. CI still passes GUARD_SRC explicitly; this only fixes the local run.
const roots = (process.env.GUARD_SRC ?? 'app/src/main').split(',').map((d) => d.trim()).filter(Boolean);
const allowPath = join(here, 'no-local-bucketing-allow.json');

const SOURCE = /\.(ts|tsx|js|mjs|cjs|py|php|kt|java|swift)$/;
const SKIP_DIR = /^(node_modules|\.git|dist|build|vendor|target|__pycache__|\.venv|Pods|DerivedData)$/;

/** Hashing primitives, and the bucket arithmetic itself. */
const PATTERNS = [
  [/\b(sha-?256|sha-?1|md5|murmur|fnv|crc32|xxhash)\b/i, 'a hashing primitive'],
  [/%\s*10000\b|\bmod\s+10000\b/i, 'modulo the platform bucket count'],
  [/\bBUCKETS?_PER_|\bTOTAL_BUCKETS\b/i, 'bucket arithmetic'],
  [/createHash|MessageDigest|hashlib|CryptoKit|\bDigest\b/, 'a hash construction'],
];

function walk(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir)) {
    if (SKIP_DIR.test(name)) continue;
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (SOURCE.test(name)) out.push(p);
  }
  return out;
}

const allow = existsSync(allowPath) ? JSON.parse(readFileSync(allowPath, 'utf8')) : {};
const seen = new Set();
const hits = [];

// A scan root that does not exist is a configuration error, not an empty result. Without this the
// guard printed "OK -- scanned <dir>" and exited 0 having read no files at all: a rename of the
// source directory, or a dropped GUARD_SRC, silently disarmed it. The stale-allowlist check below
// only caught it here by luck, and would not catch it in a repo whose allowlist is empty -- which
// is every other SDK repo this script is meant to be vendored into.
const missing = roots.filter((r) => !existsSync(join(root, r)));
let scanned = 0;

for (const r of roots) {
  for (const file of walk(join(root, r))) {
    scanned += 1;
    const rel = relative(root, file);
    readFileSync(file, 'utf8').split('\n').forEach((line, i) => {
      if (/^\s*(\/\/|#|\*|--)/.test(line)) return; // a comment explaining the rule is not a breach
      for (const [re, what] of PATTERNS) {
        if (!re.test(line)) continue;
        const key = `${rel}:${i + 1}`;
        if (allow[key] || allow[rel]) { seen.add(allow[key] ? key : rel); return; }
        hits.push(`${key}  ${what}\n      ${line.trim().slice(0, 100)}`);
        return;
      }
    });
  }
}

const problems = [];
if (missing.length) {
  problems.push(
    `scan root(s) do not exist, so nothing was scanned: ${missing.join(', ')} ` +
      `(set GUARD_SRC to the source root, or GUARD_ROOT to the repo root)`
  );
}
if (hits.length) {
  problems.push(
    `bucket derivation must be server-only (EXP-ASSIGN-001, EXP-ASSIGN-005) — ${hits.length} occurrence(s):\n    ` +
      hits.join('\n    ')
  );
}

// The reverse check. Without it the allowlist becomes a place to park anything, and a stale entry
// hides the fact that its justification no longer applies.
const stale = Object.keys(allow).filter((k) => !seen.has(k));
if (stale.length) {
  problems.push(`allowlist entries that no longer match anything: ${stale.join(', ')}`);
}
const unexplained = Object.entries(allow).filter(([, v]) => !String(v ?? '').trim());
if (unexplained.length) {
  problems.push(`allowlist entries with no reason: ${unexplained.map(([k]) => k).join(', ')}`);
}

if (problems.length) {
  console.error('no-local-bucketing FAILED');
  for (const p of problems) console.error(`  - ${p}`);
  process.exit(1);
}
console.log(
  `no-local-bucketing OK — scanned ${scanned} file(s) under ${roots.join(', ')}, ` +
    `${Object.keys(allow).length} documented allowance(s)`
);
