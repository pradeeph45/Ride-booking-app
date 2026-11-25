import { describe, expect, it } from 'vitest';
import { haversineKm, isValidLatLng, roundCoord, shortenAddress } from './geo';

describe('haversineKm', () => {
  it('measures a known distance: MG Road to Koramangala is about 5 km', () => {
    const km = haversineKm({ lat: 12.9756, lng: 77.605 }, { lat: 12.9352, lng: 77.6245 });

    expect(km).toBeGreaterThan(4.5);
    expect(km).toBeLessThan(5.5);
  });

  it('is zero for the same point and symmetric', () => {
    const a = { lat: 12.97, lng: 77.59 };
    const b = { lat: 13.19, lng: 77.7 };

    expect(haversineKm(a, a)).toBe(0);
    expect(haversineKm(a, b)).toBeCloseTo(haversineKm(b, a), 10);
  });
});

describe('isValidLatLng', () => {
  it('accepts real coordinates and rejects impossible ones', () => {
    expect(isValidLatLng(12.97, 77.59)).toBe(true);
    expect(isValidLatLng(-90, 180)).toBe(true);
    expect(isValidLatLng(91, 0)).toBe(false);
    expect(isValidLatLng(0, -181)).toBe(false);
    expect(isValidLatLng(Number.NaN, 0)).toBe(false);
    expect(isValidLatLng(0, Number.POSITIVE_INFINITY)).toBe(false);
  });
});

describe('roundCoord', () => {
  it('keeps six decimals, about 10 cm, which is plenty and keeps payloads tidy', () => {
    expect(roundCoord(12.97561234567)).toBe(12.975612);
    expect(roundCoord(77.6)).toBe(77.6);
  });
});

describe('shortenAddress', () => {
  it('keeps the first parts of a long OpenStreetMap address', () => {
    const long = 'Mahatma Gandhi Road, Shanthala Nagar, Bengaluru, Bangalore North, Karnataka, 560001, India';

    expect(shortenAddress(long)).toBe('Mahatma Gandhi Road, Shanthala Nagar, Bengaluru');
  });

  it('skips parts that are only numbers such as postcodes', () => {
    expect(shortenAddress('560001, Church Street, Bengaluru')).toBe('Church Street, Bengaluru');
  });

  it('never exceeds a sensible length and handles empty input', () => {
    expect(shortenAddress('x'.repeat(200)).length).toBeLessThanOrEqual(80);
    expect(shortenAddress('')).toBe('');
  });
});
