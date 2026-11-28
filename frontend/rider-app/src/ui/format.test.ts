import { describe, expect, it } from 'vitest';
import { describeDevice, formatRating, initialsOf, roleLabel, secondsLeft, timeAgo } from './format';

describe('describeDevice', () => {
  it('turns the long Chrome-on-Windows user agent from the screenshot into a readable name', () => {
    const ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/158.0.0.0 Sa';

    expect(describeDevice(ua)).toEqual({ label: 'Chrome on Windows', icon: '💻' });
  });

  it('recognises other browsers and platforms', () => {
    expect(describeDevice('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36 Edg/120.0').label)
      .toBe('Edge on Windows');
    expect(describeDevice('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Gecko/20100101 Firefox/121.0').label)
      .toBe('Firefox on macOS');
    expect(describeDevice('Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Mobile Safari/604.1'))
      .toEqual({ label: 'Safari on iOS', icon: '📱' });
    expect(describeDevice('Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36'))
      .toEqual({ label: 'Chrome on Android', icon: '📱' });
  });

  it('labels command-line and API clients without pretending they are browsers', () => {
    expect(describeDevice('curl/8.7.1')).toEqual({ label: 'Command-line client', icon: '⌨️' });
    expect(describeDevice('Java-http-client/21.0.10').icon).toBe('⌨️');
  });

  it('handles missing and unrecognised values safely', () => {
    expect(describeDevice(null)).toEqual({ label: 'Unknown device', icon: '❔' });
    expect(describeDevice('   ')).toEqual({ label: 'Unknown device', icon: '❔' });
    expect(describeDevice('SomethingOdd/1.0 with a very long description that goes on and on forever').label.length)
      .toBeLessThanOrEqual(41);
  });
});

describe('timeAgo', () => {
  const now = new Date('2026-11-24T12:00:00Z');
  const ago = (ms: number) => new Date(now.getTime() - ms).toISOString();

  it('uses friendly relative wording for recent times', () => {
    expect(timeAgo(ago(10_000), now)).toBe('just now');
    expect(timeAgo(ago(5 * 60_000), now)).toBe('5 min ago');
    expect(timeAgo(ago(3 * 3_600_000), now)).toBe('3 h ago');
    expect(timeAgo(ago(2 * 86_400_000), now)).toBe('2 days ago');
    expect(timeAgo(ago(86_400_000), now)).toBe('1 day ago');
  });

  it('falls back to a short calendar date for older times', () => {
    expect(timeAgo(ago(30 * 86_400_000), now)).toMatch(/\d{1,2} \w{3} 2026/);
  });

  it('never shows a negative time for clocks slightly in the future', () => {
    expect(timeAgo(new Date(now.getTime() + 5_000).toISOString(), now)).toBe('just now');
  });
});

describe('small helpers', () => {
  it('builds avatar initials from an email', () => {
    expect(initialsOf('demo.rider@example.com')).toBe('DR');
    expect(initialsOf('priya@example.com')).toBe('P');
    expect(initialsOf('')).toBe('?');
  });

  it('shows role names in plain words', () => {
    expect(roleLabel('RIDER')).toBe('Rider');
    expect(roleLabel('DRIVER')).toBe('Driver');
    expect(roleLabel('ADMIN')).toBe('Admin');
  });
});

describe('secondsLeft', () => {
  const now = new Date('2026-11-24T12:00:00Z');

  it('counts down to a deadline, rounding up so the last second still shows 1', () => {
    expect(secondsLeft('2026-11-24T12:00:15Z', now)).toBe(15);
    expect(secondsLeft('2026-11-24T12:00:00.400Z', now)).toBe(1);
  });

  it('is zero at and after the deadline, never negative', () => {
    expect(secondsLeft('2026-11-24T12:00:00Z', now)).toBe(0);
    expect(secondsLeft('2026-11-24T11:59:00Z', now)).toBe(0);
  });
});

describe('formatRating', () => {
  it('shows one decimal and a count', () => {
    expect(formatRating(4.83, 12)).toBe('★ 4.8 (12 ratings)');
  });

  it('says rating, not ratings, for exactly one', () => {
    expect(formatRating(5, 1)).toBe('★ 5.0 (1 rating)');
  });

  it('shows nothing until someone has rated', () => {
    expect(formatRating(null, 0)).toBeNull();
  });
});
