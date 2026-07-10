import { describe, expect, it } from 'vitest';
import { classifyWsFrame } from './client';

describe('classifyWsFrame', () => {
  it('routes a retention.swept frame to the library-event channel (drives a refetch)', () => {
    const routed = classifyWsFrame({
      type: 'retention.swept',
      payload: { freedBytes: 1000, deletedIds: [7] },
    });
    expect(routed).not.toBeNull();
    expect(routed?.kind).toBe('libraryEvent');
    if (routed?.kind === 'libraryEvent') {
      expect(routed.envelope.type).toBe('retention.swept');
      expect(routed.envelope.payload).toEqual({ freedBytes: 1000, deletedIds: [7] });
    }
  });

  it('routes the match.* frames to the library-event channel', () => {
    for (const type of ['match.recorded', 'match.enriched', 'match.enrichFailed']) {
      expect(classifyWsFrame({ type, payload: { id: 1 } })?.kind).toBe('libraryEvent');
    }
  });

  it('routes a low-disk error frame to the disk-warning channel (banner state)', () => {
    const routed = classifyWsFrame({
      type: 'error',
      payload: { scope: 'disk', freeBytes: 500, thresholdBytes: 1000, message: 'Low disk space' },
    });
    expect(routed?.kind).toBe('diskWarning');
    if (routed?.kind === 'diskWarning') {
      expect(routed.warning).toEqual({
        freeBytes: 500,
        thresholdBytes: 1000,
        message: 'Low disk space',
      });
    }
  });

  it('ignores an error frame with a non-disk scope', () => {
    expect(classifyWsFrame({ type: 'error', payload: { scope: 'obs', message: 'x' } })).toBeNull();
  });

  it('ignores a disk error frame missing required numeric fields', () => {
    expect(classifyWsFrame({ type: 'error', payload: { scope: 'disk', message: 'x' } })).toBeNull();
  });

  it('routes a valid status frame and drops a malformed one', () => {
    const good = classifyWsFrame({
      type: 'status',
      payload: {
        gsi: { connected: true, lastFrameAgoMs: 10 },
        obs: { connected: true, sceneActive: false, recording: true },
        fsm: { state: 'RECORDING', activeMatchId: 3 },
      },
    });
    expect(good?.kind).toBe('status');
    if (good?.kind === 'status') {
      expect(good.status.recording).toBe(true);
      expect(good.status.fsmState).toBe('RECORDING');
    }
    expect(classifyWsFrame({ type: 'status', payload: { gsi: {} } })).toBeNull();
  });

  it('routes clip frames and drops a clip payload without a numeric parentMatchId', () => {
    expect(
      classifyWsFrame({ type: 'clip.created', payload: { parentMatchId: 5, id: 1 } })?.kind,
    ).toBe('clip');
    expect(classifyWsFrame({ type: 'clip.created', payload: { id: 1 } })).toBeNull();
  });

  it('ignores unknown types and malformed envelopes', () => {
    expect(classifyWsFrame({ type: 'something.new', payload: {} })).toBeNull();
    expect(classifyWsFrame({ payload: {} })).toBeNull();
    expect(classifyWsFrame(null)).toBeNull();
    expect(classifyWsFrame('nope')).toBeNull();
  });
});
