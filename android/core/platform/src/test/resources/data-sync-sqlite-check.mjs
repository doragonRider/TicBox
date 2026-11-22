// Runs the exact production DDL on native SQLite. Android SQLiteOpenHelper execution is separate.
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
const schema = readFileSync(new URL('../../main/kotlin/dev/ticbox/companion/platform/data/DataSyncSqlSchema.kt', import.meta.url), 'utf8');
const sql = [...schema.matchAll(/"""([\s\S]*?)"""/g)].map(match => match[1]);
const retirementSource = readFileSync(new URL('../../main/kotlin/dev/ticbox/companion/platform/data/NotificationAssetRetirementSql.kt', import.meta.url), 'utf8');
const retirementSql = retirementSource.match(/"""([\s\S]*?)"""/)?.[1];
assert.ok(retirementSql, 'Production notification retirement statement must exist');
const db = new DatabaseSync(':memory:');
db.exec('PRAGMA foreign_keys=ON');
// Exercise the current development schema directly.
for (const statement of sql) db.exec(statement);
db.exec("INSERT INTO scopes VALUES('s','AA:BB:CC:DD:EE:FF','phone','watch',NULL)");
db.exec("INSERT INTO sequences VALUES('s',0)");
const putItem = db.prepare('INSERT OR REPLACE INTO items VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)');
const item = (id, seq, deleted = 0) => [id,'s','p','c',`wear://watch/${id}`,'watch',seq,seq,deleted,new Uint8Array([1]),null,1,0,new Uint8Array([0x08,1])];
const cursor = db.prepare("INSERT OR REPLACE INTO cursors VALUES('s','watch',?)");
db.exec('BEGIN'); putItem.run(...item('a',1)); cursor.run(1); db.exec('COMMIT');
// A crash/failure after mutation but before commit must roll back BOTH rows and cursor.
db.exec('BEGIN'); putItem.run(...item('a',2,1)); cursor.run(2); db.exec('ROLLBACK');
assert.equal(db.prepare("SELECT sequence_id FROM cursors WHERE scope_id='s'").get().sequence_id,1);
assert.equal(db.prepare("SELECT deleted FROM items WHERE item_id='a'").get().deleted,0);
db.exec("INSERT INTO item_assets VALUES('a',0,'0','asset',4)");
db.exec("INSERT INTO assets VALUES('s','asset',X'0102',1)");
db.exec("INSERT INTO asset_acl VALUES('s','asset','p','c')");
const missing = db.prepare(`SELECT DISTINCT i.package_name,i.package_digest,r.digest FROM items i
 JOIN item_assets r ON r.item_id=i.item_id
 LEFT JOIN assets a ON a.scope_id=i.scope_id AND a.digest=r.digest
 LEFT JOIN asset_acl p ON p.scope_id=i.scope_id AND p.digest=r.digest
 AND p.package_name=i.package_name AND p.package_digest=i.package_digest
 WHERE i.scope_id=? AND i.deleted=0 AND r.digest<>'' AND (a.body IS NULL OR p.digest IS NULL)`);
assert.equal(missing.all('s').length,0);
db.exec("DELETE FROM asset_acl"); assert.equal(missing.all('s').length,1);
db.exec("INSERT INTO asset_acl VALUES('s','asset','p','c')");
db.exec("UPDATE assets SET received_at=2 WHERE scope_id='s' AND digest='asset'");
assert.equal(db.prepare('SELECT hex(body) AS bytes FROM assets').get().bytes,'0102');
assert.equal(db.prepare('SELECT count(*) AS count FROM asset_acl').get().count,1);
// Replacement tombstones remove obsolete references, not the cached file or its permissions.
putItem.run(...item('a',3,1));
assert.equal(db.prepare('SELECT count(*) AS count FROM item_assets').get().count,0);
assert.equal(db.prepare('SELECT count(*) AS count FROM assets').get().count,1);
assert.equal(db.prepare('PRAGMA foreign_key_check').all().length,0);
// Observation pagination is peer-isolated and retention removes only the oldest ids.
const sample = db.prepare('INSERT INTO observation_samples(peer,kind,received_at,battery,steps,goal) VALUES(?,?,?,?,?,?)');
sample.run('peer-a','BATTERY',10,80,null,null);
sample.run('peer-b','BATTERY',11,20,null,null);
sample.run('peer-a','STEPS',12,null,123,10000);
sample.run('peer-a','BATTERY',13,79,null,null);
assert.equal(db.prepare("SELECT battery FROM observation_samples WHERE peer=? AND kind=? ORDER BY sample_id DESC LIMIT 1").get('peer-a','BATTERY').battery,79);
const page = db.prepare('SELECT sample_id FROM observation_samples WHERE peer=? AND sample_id<? ORDER BY sample_id DESC LIMIT ?').all('peer-a',4,2);
assert.deepEqual(page.map(row => row.sample_id),[3,1]);
db.prepare('DELETE FROM observation_samples WHERE sample_id <= (SELECT sample_id FROM observation_samples ORDER BY sample_id DESC LIMIT 1 OFFSET ?)').run(3);
assert.deepEqual(db.prepare('SELECT sample_id FROM observation_samples ORDER BY sample_id').all().map(row => row.sample_id),[2,3,4]);
const putCloud = db.prepare('INSERT OR REPLACE INTO cloud_cache VALUES(?,?,?,?,?,?,?,?,?,?,?)');
const putWindow = db.prepare('INSERT OR REPLACE INTO cloud_sync_windows VALUES(?,?,?,?,?)');
const cloudRow = (owner, value) => [owner,'POINT','same-key','derived:com.mobvoi.fitness','step_count',10,20,'UTC',0,JSON.stringify({value}),30];
db.exec('BEGIN'); putCloud.run(...cloudRow('owner-a',10)); putWindow.run('owner-a','POINT',10,20,30); db.exec('COMMIT');
db.exec('BEGIN'); putCloud.run(...cloudRow('owner-a',99)); putWindow.run('owner-a','POINT',20,30,31); db.exec('ROLLBACK');
assert.equal(JSON.parse(db.prepare('SELECT payload FROM cloud_cache WHERE owner_key=?').get('owner-a').payload).value,10);
assert.equal(db.prepare('SELECT count(*) AS count FROM cloud_sync_windows').get().count,1);
putCloud.run(...cloudRow('owner-b',40));
assert.equal(db.prepare('SELECT count(*) AS count FROM cloud_cache').get().count,2);
db.prepare('DELETE FROM cloud_cache WHERE owner_key=?').run('owner-a');
assert.equal(JSON.parse(db.prepare('SELECT payload FROM cloud_cache').get().payload).value,40);
// Only an explicitly retired notification digest is eligible. Keep staged assets, other owners,
// every live reference (including another route), and rows of another device scope intact.
const retire = db.prepare(retirementSql);
const release = (digest, scope = 's', pkg = 'p', cert = 'c') => retire.run(scope, digest, pkg, cert, pkg, cert).changes;
const putAsset = (scope, digest) => {
 db.prepare("INSERT INTO assets VALUES(?,?,X'010203',1)").run(scope, digest);
 db.prepare('INSERT INTO asset_acl VALUES(?,?,?,?)').run(scope,digest,'p','c');
};
putAsset('s','staged');
putAsset('s','shared-live');
putItem.run(...item('live',4));
db.exec("UPDATE items SET package_name='different-route' WHERE item_id='live'");
db.exec("INSERT INTO item_assets VALUES('live',0,'0','shared-live',4)");
assert.equal(release('shared-live'),0);
putAsset('s','another-owner');
db.exec("INSERT INTO asset_acl VALUES('s','another-owner','other-package','other-cert')");
assert.equal(release('another-owner'),0);
assert.equal(release('asset','s','wrong','c'),0);
db.exec("INSERT INTO scopes VALUES('s2','11:22:33:44:55:66','phone2','watch2',NULL)");
putAsset('s2','asset');
db.exec('BEGIN'); assert.equal(release('asset'),1); db.exec('ROLLBACK');
assert.equal(db.prepare("SELECT hex(body) AS bytes FROM assets WHERE scope_id='s' AND digest='asset'").get().bytes,'0102');
assert.equal(release('asset'),1);
assert.equal(release('asset'),0); // Idempotent: metadata/ACL stay present after bytes are released.
assert.equal(db.prepare("SELECT body FROM assets WHERE scope_id='s' AND digest='asset'").get().body,null);
assert.equal(db.prepare("SELECT count(*) AS n FROM asset_acl WHERE scope_id='s' AND digest='asset'").get().n,1);
assert.equal(db.prepare("SELECT hex(body) AS bytes FROM assets WHERE scope_id='s2' AND digest='asset'").get().bytes,'010203');
assert.equal(db.prepare("SELECT hex(body) AS bytes FROM assets WHERE scope_id='s' AND digest='staged'").get().bytes,'010203');
assert.equal(db.prepare("SELECT count(*) AS n FROM items WHERE item_id='live'").get().n,1);
assert.equal(JSON.parse(db.prepare('SELECT payload FROM cloud_cache').get().payload).value,40);
assert.equal(db.prepare('PRAGMA integrity_check').get().integrity_check,'ok');
db.close();
console.log(`PASS ${sql.length} production SQLite DDL statements; atomic rollback, ACL/body retention, missing-asset joins, tombstones, observation pagination/retention, current-schema creation, cloud account isolation, window rollback and scoped retired-notification Asset cleanup with shared/staged/live-reference protection`);
