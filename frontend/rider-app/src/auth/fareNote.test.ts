import { describe, expect, it } from 'vitest';
import { fareNote } from './bookings';

const done = { status: 'COMPLETED' as const, currency: 'INR', estimatedFare: 170, finalFare: 132.5,
  fareSource: 'METERED' as const, actualDistanceKm: 5, actualMinutes: 15 };

describe('fareNote', () => {
  it('says nothing until the trip is completed', () => {
    expect(fareNote({ ...done, status: 'STARTED', finalFare: null, fareSource: null })).toBeNull();
  });

  it('shows what was driven next to the quote for a metered fare', () => {
    const note = fareNote(done)!;
    expect(note).toContain('Metered: 5.0 km · 15 min');
    expect(note).toContain('quoted');
    expect(note).not.toContain('capped');
  });

  it('says so when the fare was held at the ceiling', () => {
    expect(fareNote({ ...done, finalFare: 255 })).toContain('capped at 1.5× the quote');
  });

  it('explains an estimate-priced trip', () => {
    expect(fareNote({ ...done, fareSource: 'ESTIMATE', actualDistanceKm: null, actualMinutes: null, finalFare: 170 }))
      .toContain('Fare as quoted');
  });

  it('treats trips completed before metering existed as quoted', () => {
    expect(fareNote({ ...done, fareSource: undefined, actualDistanceKm: undefined, actualMinutes: undefined })).toContain('Fare as quoted');
  });
});
