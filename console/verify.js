/* Thakaan console verifier: checks signed crew-status messages and the hash-chained exposure log.
 * Works in the browser and in Node (>= 19, WebCrypto). No network, no dependencies.
 * A valid chain proves entries were not edited after recording, NOT that the observations were true. */
(function (root) {
  const enc = new TextEncoder();
  const hex2buf = (h) => Uint8Array.from((h.match(/../g) || []), (b) => parseInt(b, 16));
  const buf2hex = (b) => [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, '0')).join('');
  const cr = (root.crypto && root.crypto.subtle) ? root.crypto : require('node:crypto').webcrypto;
  const GENESIS = '0'.repeat(64);

  async function sha256Hex(s) { return buf2hex(await cr.subtle.digest('SHA-256', enc.encode(s))); }

  // Java's SHA256withECDSA gives a DER signature; WebCrypto wants raw r||s (64 bytes).
  function derToRaw(der) {
    let o = 2;
    if (der[1] & 0x80) o = 2 + (der[1] & 0x7f);
    const rl = der[o + 1]; let r = der.slice(o + 2, o + 2 + rl); o = o + 2 + rl;
    const sl = der[o + 1]; let s = der.slice(o + 2, o + 2 + sl);
    const fix = (x) => { while (x.length > 32 && x[0] === 0) x = x.slice(1); const out = new Uint8Array(32); out.set(x, 32 - x.length); return out; };
    const raw = new Uint8Array(64); raw.set(fix(r), 0); raw.set(fix(s), 32); return raw;
  }

  async function verifySig(keyHex, dataBytes, sigHex) {
    try {
      const key = await cr.subtle.importKey('spki', hex2buf(keyHex), { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
      return await cr.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key, derToRaw(hex2buf(sigHex)), dataBytes);
    } catch (e) { return false; }
  }

  /** Returns {ok, status, reason}. A message is accepted only if its signature verifies and the nonce matches (when given). */
  async function verifyStatus(text, expectedNonce) {
    try {
      const o = JSON.parse(text);
      if (!(await verifySig(o.key, enc.encode(o.body), o.sig))) return { ok: false, reason: 'signature does not match' };
      const b = JSON.parse(o.body);
      if (expectedNonce && b.nonce !== expectedNonce) return { ok: false, reason: 'nonce differs: possible replay' };
      return { ok: true, status: b, hardwareKey: !!o.hardwareKey, key: o.key };
    } catch (e) { return { ok: false, reason: 'not a valid message' }; }
  }

  const canonical = (i, t, sun, rest, peak, prov, prev) => `v1|${i}|${t}|${Number(sun).toFixed(3)}|${Number(rest).toFixed(3)}|${peak}|${prov}|${prev}`;

  function banner(v) {
    if (v.status === 'OK') return 'Chain OK: entries not edited since recording (this does not prove the readings were true)';
    if (v.status === 'BROKEN') return `Chain BROKEN at entry ${v.firstBadIndex ?? '?'}: ${v.reason}`;
    return 'No entries';
  }

  async function verifyChain(text, expectedHead, expectedCount) {
    const fail = (idx, reason) => { const v = { status: 'BROKEN', firstBadIndex: idx, reason }; v.banner = banner(v); return v; };
    let o; try { o = JSON.parse(text); } catch (e) { return fail(null, 'not a valid export'); }
    const arr = o.entries; if (!Array.isArray(arr)) return fail(null, 'entries missing');
    if (arr.length === 0) { const v = { status: 'EMPTY', firstBadIndex: null, reason: '' }; v.banner = banner(v); return v; }
    let prev = GENESIS;
    for (let k = 0; k < arr.length; k++) {
      const e = arr[k];
      if (e.i !== k) return fail(k, 'entry out of order or missing');
      if (e.prev !== prev) return fail(k, 'link to the previous entry does not match');
      const hash = await sha256Hex(canonical(k, e.t, e.sun, e.rest, e.peak, e.prov, prev));
      if (hash !== e.hash) return fail(k, 'entry content was changed');
      if (!(await verifySig(o.publicKey, enc.encode(hash), e.sig))) return fail(k, 'signature does not match');
      prev = hash;
    }
    if (expectedCount != null && arr.length !== expectedCount) return fail(arr.length, `expected ${expectedCount} entries, found ${arr.length}`);
    if (expectedHead != null && prev !== expectedHead) return fail(arr.length - 1, 'head hash differs from the one seen earlier');
    const v = { status: 'OK', firstBadIndex: null, reason: '', hardwareBackedKey: !!o.hardwareBackedKey, head: prev, count: arr.length };
    v.banner = banner(v); return v;
  }

  const api = { verifyStatus, verifyChain, sha256Hex };
  if (typeof module !== 'undefined' && module.exports) module.exports = api; else root.ThakaanVerify = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
