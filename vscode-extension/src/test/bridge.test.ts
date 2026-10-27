import test from 'node:test';
import assert from 'node:assert/strict';
import { FabricBridge } from '../bridge';
import { MAX_MESSAGE_BYTES } from '../protocol';

test('bridge starts disconnected', () => assert.equal(new FabricBridge(27128, 1000).connected, false));
test('protocol keeps payload bounded', () => assert.equal(MAX_MESSAGE_BYTES, 65_536));
