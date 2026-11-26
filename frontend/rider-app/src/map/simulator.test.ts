import { describe, expect, it } from 'vitest';
import { haversineKm } from './geo';
import { bearing, stepToward } from './simulator';

const MG_ROAD = { lat: 12.9756, lng: 77.605 };
const KORAMANGALA = { lat: 12.9352, lng: 77.6245 };

describe('stepToward', () => {
  it('moves the requested distance along the way to the target', () => {
    const { point, arrived } = stepToward(MG_ROAD, KORAMANGALA, 100);

    expect(arrived).toBe(false);
    expect(haversineKm(MG_ROAD, point) * 1000).toBeCloseTo(100, -1);                 // ~100 m, within tens of cm
    expect(haversineKm(point, KORAMANGALA)).toBeLessThan(haversineKm(MG_ROAD, KORAMANGALA)); // and it got closer
  });

  it('arrives exactly on the target when it is closer than one step', () => {
    const near = { lat: MG_ROAD.lat + 0.0003, lng: MG_ROAD.lng };                      // ~33 m away

    const { point, arrived } = stepToward(MG_ROAD, near, 100);

    expect(arrived).toBe(true);
    expect(point).toEqual(near);                                                       // no overshooting
  });

  it('stays put once there', () => {
    const { point, arrived } = stepToward(MG_ROAD, MG_ROAD, 100);

    expect(arrived).toBe(true);
    expect(point).toEqual(MG_ROAD);
  });

  it('reaches the target in a sensible number of steps and never moves away', () => {
    let position = MG_ROAD;
    let remaining = haversineKm(position, KORAMANGALA);
    let steps = 0;

    for (; steps < 200; steps++) {
      const next = stepToward(position, KORAMANGALA, 100);
      const left = haversineKm(next.point, KORAMANGALA);
      expect(left).toBeLessThanOrEqual(remaining + 1e-9);                              // monotonic: always closer
      position = next.point;
      remaining = left;
      if (next.arrived) break;
    }

    expect(position).toEqual(KORAMANGALA);
    expect(steps).toBeGreaterThan(40);                                                 // ~5 km at 100 m a step
    expect(steps).toBeLessThan(60);
  });
});

describe('bearing', () => {
  it('points the compass way the vehicle is heading, 0 to 360', () => {
    const o = { lat: 12.97, lng: 77.6 };

    expect(bearing(o, { lat: 13.0, lng: 77.6 })).toBeCloseTo(0, 0);                    // north
    expect(bearing(o, { lat: 12.97, lng: 77.7 })).toBeCloseTo(90, 0);                  // east
    expect(bearing(o, { lat: 12.9, lng: 77.6 })).toBeCloseTo(180, 0);                  // south
    expect(bearing(o, { lat: 12.97, lng: 77.5 })).toBeCloseTo(270, 0);                 // west
  });
});
