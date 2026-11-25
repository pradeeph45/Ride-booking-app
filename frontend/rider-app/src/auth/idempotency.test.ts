import { describe, expect, it } from 'vitest';
import { RetryKey } from './idempotency';

describe('RetryKey', () => {
  it('gives the same key for the same request, so a retry or double-click is recognised by the server', () => {
    const keys = new RetryKey();

    expect(keys.keyFor('CAR_MINI|A|B')).toBe(keys.keyFor('CAR_MINI|A|B'));
  });

  it('gives a new key when the request changes, because it is a different booking', () => {
    const keys = new RetryKey();
    const first = keys.keyFor('CAR_MINI|A|B');

    expect(keys.keyFor('BIKE|A|B')).not.toBe(first);
  });

  it('gives a new key after success, so the next booking of the same trip is not treated as a retry', () => {
    const keys = new RetryKey();
    const first = keys.keyFor('CAR_MINI|A|B');

    keys.clear();

    expect(keys.keyFor('CAR_MINI|A|B')).not.toBe(first);
  });

  it('produces keys the server accepts: 8-64 characters of letters, digits, dash or underscore', () => {
    expect(new RetryKey().keyFor('x')).toMatch(/^[A-Za-z0-9_-]{8,64}$/);
  });

  it('never repeats a key across different requests', () => {
    const keys = new RetryKey();
    const seen = new Set(Array.from({ length: 50 }, (_, i) => keys.keyFor(`request-${i}`)));

    expect(seen.size).toBe(50);
  });
});
