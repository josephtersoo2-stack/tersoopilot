import test from 'node:test';
import assert from 'node:assert/strict';
import { parseCookies } from '../src/lib/cookies.js';

test('Netscape import preserves HttpOnly and zero expiry', () => {
  const [cookie] = parseCookies('# Netscape HTTP Cookie File\n#HttpOnly_.example.com\tTRUE\t/\tTRUE\t0\tsession\tvalue');
  assert.equal(cookie.isHttpOnly, true);
  assert.equal(cookie.isSecure, true);
  assert.equal(cookie.expiry, 0);
  assert.equal(cookie.domain, '.example.com');
});
test('empty values and JSON arrays are retained', () => {
  assert.equal(parseCookies('example.com\tFALSE\t/\tFALSE\t10\tname\t')[0].value, '');
  assert.deepEqual(parseCookies('[{"name":"a","value":"b","domain":"example.com"}]'), [{name:'a', value:'b', domain:'example.com'}]);
});
test('malformed rows fail instead of silently dropping input', () => {
  assert.throws(() => parseCookies('invalid-row'));
  assert.throws(() => parseCookies('[broken]'));
});
