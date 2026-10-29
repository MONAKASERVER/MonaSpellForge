import test from 'node:test';
import assert from 'node:assert/strict';
import { extractPairingCode, STARTER_TEMPLATES } from '../quickstart';

test('pairing code accepts only an exact six digit clipboard value', () => {
  assert.equal(extractPairingCode(' 059825\r\n'), '059825');
  assert.equal(extractPairingCode('code: 059825'), undefined);
  assert.equal(extractPairingCode('05982'), undefined);
});

test('quick start includes safe starter spells', () => {
  assert.ok(STARTER_TEMPLATES.length >= 3);
  assert.ok(STARTER_TEMPLATES.every(template => template.source.includes('on cast')));
  assert.ok(STARTER_TEMPLATES.every(template => !template.source.includes('TRUE_DAMAGE')));
});

