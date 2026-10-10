// Run: node console/verify_test.js   (after the Kotlin test `writeFixturesForTheBrowserVerifier` has produced app/build/console-fixtures)
const fs = require('node:fs');
const path = require('node:path');
const V = require('./verify.js');

const dir = path.join(__dirname, '..', 'app', 'build', 'console-fixtures');
const read = (f) => fs.readFileSync(path.join(dir, f), 'utf8');
let failed = 0;
const check = (name, cond) => { console.log((cond ? 'PASS ' : 'FAIL ') + name); if (!cond) failed++; };

(async () => {
  const st = await V.verifyStatus(read('status.json'), 'abc');
  check('a status sealed on the Kotlin side verifies in the browser verifier', st.ok && st.status.level === 'STRAINED');
  check('reasons are coarse', st.ok && st.status.reasons.every((r) => !/bpm|heart|dizz/i.test(r)));
  check('wrong nonce is rejected as a replay', !(await V.verifyStatus(read('status.json'), 'zzz')).ok);
  const tampered = JSON.parse(read('status.json')); tampered.body = tampered.body.replace('STRAINED', 'COOL');
  check('a changed level does not verify', !(await V.verifyStatus(JSON.stringify(tampered))).ok);

  const ok = await V.verifyChain(read('chain_ok.json'));
  check('untouched chain: Chain OK', ok.status === 'OK' && ok.banner.startsWith('Chain OK') && /does not prove/.test(ok.banner));
  const bad = await V.verifyChain(read('chain_bad.json'));
  check('one edited value: Chain BROKEN at the right entry', bad.status === 'BROKEN' && bad.firstBadIndex === 2);

  const o = JSON.parse(read('chain_ok.json'));
  const head = o.head, count = o.count;
  o.entries.pop();
  check('truncated chain is only caught with an earlier head', (await V.verifyChain(JSON.stringify(o))).status === 'OK' && (await V.verifyChain(JSON.stringify(o), head)).status === 'BROKEN' && (await V.verifyChain(JSON.stringify(o), null, count)).status === 'BROKEN');
  const o2 = JSON.parse(read('chain_ok.json')); [o2.entries[1], o2.entries[2]] = [o2.entries[2], o2.entries[1]];
  check('reordered entries are detected', (await V.verifyChain(JSON.stringify(o2))).status === 'BROKEN');
  check('garbage is BROKEN, not a crash', (await V.verifyChain('nope')).status === 'BROKEN');

  console.log('failed:', failed);
  process.exit(failed ? 1 : 0);
})();
