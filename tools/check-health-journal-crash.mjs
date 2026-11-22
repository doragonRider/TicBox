import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { createHash } from 'node:crypto';
import { copyFileSync, existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

// Only test-generated databases are admitted; no live/OEM path or SQL comes from an external input.
const [, , first, ...args] = process.argv;
if (first === '--owned-child') {
  const [path, scenario] = args;
  const db = new DatabaseSync(path);
  db.exec('PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL;');
  if (scenario === 'after-trigger-fail') {
    db.exec("CREATE TRIGGER fixture_oem_fail AFTER INSERT ON data_point BEGIN SELECT RAISE(FAIL,'fixture'); END;");
  }
  db.exec('BEGIN IMMEDIATE;');
  if (scenario === 'savepoint-rollback') db.exec('SAVEPOINT fixture;');
  try { db.exec("INSERT INTO data_point VALUES(1,'synthetic',1,0);"); }
  catch (error) { if (scenario !== 'after-trigger-fail') throw error; }
  if (scenario === 'savepoint-rollback') db.exec('ROLLBACK TO fixture; RELEASE fixture;');
  if (scenario !== 'before-commit') db.exec('COMMIT;');
  process.kill(process.pid, 'SIGKILL');
} else {
  const fixture = resolve(first ?? '');
  assert.match(fixture.replaceAll('\\', '/'), /\/build\/journal-fixtures\/health\.db$/);
  assert(existsSync(fixture), 'Run the watch Release tests to generate the production-trigger fixture.');
  const hash = () => createHash('sha256').update(readFileSync(fixture)).digest('hex');
  const before = hash();
  const directory = mkdtempSync(join(dirname(fixture), 'owned-crash-'));
  const cases = ['before-commit', 'after-commit', 'savepoint-rollback', 'after-trigger-fail'];
  try {
    for (const scenario of cases) {
      const path = join(directory, `${scenario}.db`);
      copyFileSync(fixture, path);
      const child = spawnSync(process.execPath, [fileURLToPath(import.meta.url), '--owned-child', path, scenario], {
        timeout: 15_000, encoding: 'utf8', maxBuffer: 64 * 1024,
      });
      assert.equal(child.error, undefined);
      assert.equal(child.signal, 'SIGKILL', `${scenario}: owned child did not reach the intended crash boundary`);
      const db = new DatabaseSync(path);
      try {
        assert.equal(db.prepare('PRAGMA integrity_check').get().integrity_check, 'ok');
        const rows = db.prepare('SELECT COUNT(*) AS n FROM data_point').get().n;
        const events = db.prepare('SELECT COUNT(*) AS n FROM ticwatch_journal_v1_events').get().n;
        const committed = scenario === 'after-commit' || scenario === 'after-trigger-fail';
        assert.equal(rows, committed ? 1 : 0, scenario);
        assert.equal(events, committed ? 1 : 0, scenario);
        if (scenario === 'after-commit') {
          const item = db.prepare('SELECT row_key,pending FROM ticwatch_journal_v1_events').get();
          assert.equal(item.row_key, '1'); assert.equal(item.pending, 0);
        }
      } finally { db.close(); }
    }
    assert.equal(hash(), before, 'The original synthetic fixture was modified.');
    console.log(JSON.stringify({ check: 'health-journal-owned-process-crash', cases: cases.length, status: 'pass', fixtureSha256: before }));
  } finally { rmSync(directory, { recursive: true, force: true }); }
}
