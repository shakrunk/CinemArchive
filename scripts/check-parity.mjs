#!/usr/bin/env node
// Cross-client parity gate.
//
// Every user-facing commit (`feat`, `fix`, or breaking) that changes shipped source in exactly one
// client must say what happens on the other client, via a `Parity:` trailer:
//
//   Parity: #123              the other client's gap is tracked in issue #123
//   Parity: n/a: <reason>     no counterpart is needed (client-specific bug, platform-only UI, ...)
//
// Commits that touch both clients pass automatically. Commits already pushed without a trailer can be
// declared from the pull request body instead, one line per commit:
//
//   Parity-Override: <sha, 7+ chars> #123
//   Parity-Override: <sha, 7+ chars> n/a: <reason>
//
// Usage: node scripts/check-parity.mjs [base] [head]     (defaults: origin/main HEAD)
// Env:   PR_BODY, GITHUB_TOKEN, GITHUB_REPOSITORY, GITHUB_STEP_SUMMARY (all optional)

import { execFileSync } from 'node:child_process';
import { appendFileSync } from 'node:fs';

const [base = 'origin/main', head = 'HEAD'] = process.argv.slice(2);
const GATED_TYPES = new Set(['feat', 'fix']);

function git(...args) {
  return execFileSync('git', args, { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
}

function clientOf(path) {
  if (/(\.test\.|\.spec\.|\/__tests__\/|\/test\/)/.test(path)) return null;
  if (path.startsWith('apps/web/src/')) return 'web';
  if (path.startsWith('apps/android/') && path.includes('/src/main/')) return 'android';
  return null;
}

// Returns { kind: 'issues', issues: [n...] } | { kind: 'na', reason } | { kind: 'invalid', value }.
function parseDeclaration(value) {
  const v = value.trim();
  const issues = [...v.matchAll(/#(\d+)/g)].map((m) => Number(m[1]));
  if (issues.length > 0 && /^(#\d+[\s,]*)+$/.test(v)) return { kind: 'issues', issues };
  const na = v.match(/^n\/?a\b[\s:—–-]*(.*)$/i);
  if (na && na[1].trim().length >= 3) return { kind: 'na', reason: na[1].trim() };
  return { kind: 'invalid', value: v };
}

function readOverrides(body) {
  const overrides = [];
  for (const m of (body ?? '').matchAll(/^\s*Parity-Override:\s*([0-9a-f]{7,40})\s+(.+?)\s*$/gim)) {
    overrides.push({ sha: m[1].toLowerCase(), value: m[2] });
  }
  return overrides;
}

async function issueProblem(number) {
  const { GITHUB_TOKEN: token, GITHUB_REPOSITORY: repo } = process.env;
  if (!token || !repo) return null; // Local run: can't verify, trust the reference.
  const res = await fetch(`https://api.github.com/repos/${repo}/issues/${number}`, {
    headers: { authorization: `Bearer ${token}`, accept: 'application/vnd.github+json' },
  });
  if (res.status === 404) return `issue #${number} does not exist`;
  if (!res.ok) return null; // Don't fail the gate on an API hiccup.
  const issue = await res.json();
  if (issue.pull_request) return `#${number} is a pull request, not an issue`;
  return null;
}

const log = git('log', '--no-merges', '--format=%H%x1f%B%x1e', `${base}..${head}`);
const commits = log
  .split('\x1e')
  .map((entry) => entry.trim())
  .filter(Boolean)
  .map((entry) => {
    const [sha, message] = entry.split('\x1f');
    return { sha, message, subject: message.split('\n')[0] };
  });

const overrides = readOverrides(process.env.PR_BODY);
const failures = [];
const passes = [];

for (const commit of commits) {
  const header = commit.subject.match(/^(\w+)(?:\([^)]*\))?(!)?:/);
  if (!header) continue;
  const breaking = Boolean(header[2]) || /^BREAKING[ -]CHANGE:/m.test(commit.message);
  if (!GATED_TYPES.has(header[1]) && !breaking) continue;

  const files = git('diff-tree', '--no-commit-id', '--name-only', '-r', '--root', commit.sha)
    .split('\n')
    .filter(Boolean);
  const clients = new Set(files.map(clientOf).filter(Boolean));
  if (clients.size !== 1) continue; // Both clients (already in parity) or neither (backend/docs).

  const only = [...clients][0];
  const other = only === 'web' ? 'android' : 'web';
  const short = commit.sha.slice(0, 7);
  const trailer = commit.message.match(/^Parity:\s*(.+)$/im)?.[1];
  const override = overrides.find((o) => commit.sha.startsWith(o.sha))?.value;
  const raw = override ?? trailer;
  const label = `${short} ${commit.subject}`;

  if (!raw) {
    failures.push(`${label}\n    changes ${only} only; add \`Parity: #<issue>\` or \`Parity: n/a: <reason>\` for ${other}`);
    continue;
  }
  const decl = parseDeclaration(raw);
  if (decl.kind === 'invalid') {
    failures.push(`${label}\n    unrecognised parity declaration "${decl.value}"`);
    continue;
  }
  if (decl.kind === 'issues') {
    const problems = (await Promise.all(decl.issues.map(issueProblem))).filter(Boolean);
    if (problems.length) {
      failures.push(`${label}\n    ${problems.join('; ')}`);
      continue;
    }
  }
  const how = decl.kind === 'issues' ? `${other} gap: ${decl.issues.map((n) => `#${n}`).join(', ')}` : `n/a: ${decl.reason}`;
  passes.push(`${label}  [${how}${override ? ', via PR override' : ''}]`);
}

const lines = [`Parity check for ${base}..${head}: ${commits.length} commit(s) scanned.`];
if (passes.length) lines.push('', 'Declared:', ...passes.map((p) => `  ok  ${p}`));
if (failures.length) lines.push('', 'Missing or invalid:', ...failures.map((f) => `  !!  ${f}`));
if (!passes.length && !failures.length) lines.push('No single-client feat/fix commits in range.');
console.log(lines.join('\n'));

if (process.env.GITHUB_STEP_SUMMARY) {
  appendFileSync(process.env.GITHUB_STEP_SUMMARY, `## Cross-client parity\n\n\`\`\`\n${lines.join('\n')}\n\`\`\`\n`);
}

if (failures.length) {
  console.log(
    '\nEach single-client feat/fix must say what happens on the other client. Either amend the commit' +
      ' (if unpushed) or add a line to the pull request body:\n' +
      '  Parity-Override: <sha> #<issue>        (open a "Parity gap" issue first)\n' +
      '  Parity-Override: <sha> n/a: <reason>\n' +
      'See CONTRIBUTING.md#cross-client-parity.',
  );
  process.exit(1);
}
