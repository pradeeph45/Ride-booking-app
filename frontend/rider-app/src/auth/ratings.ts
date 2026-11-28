import { api } from './api';

/** What I gave for a trip. The other person's rating of me is never shown, only the overall average. */
export interface MyRating {
  bookingId: string;
  score: number;
  comment: string | null;
  createdAt: string;
}

/** How others rate me overall. The average is null until the first rating arrives. */
export interface RatingSummary {
  average: number | null;
  count: number;
}

/** My rating of this trip, or null if I have not given one yet. */
export async function getMyRating(bookingId: string): Promise<MyRating | null> {
  const found = await api<MyRating | undefined>(`/api/bookings/${bookingId}/rating`);
  return found ?? null; // 204 No Content arrives as undefined
}

/**
 * Rates a finished trip. Sending the same rating again is safe (a retry after a lost response answers with the
 * stored one); a different rating for the same trip is refused with 409 ALREADY_RATED.
 */
export const submitRating = (bookingId: string, score: number, comment: string) =>
  api<MyRating>(`/api/bookings/${bookingId}/rating`, {
    method: 'POST',
    body: { score, ...(comment.trim() ? { comment: comment.trim() } : {}) },
  });

export const getMyRatingSummary = () => api<RatingSummary>('/api/ratings/me');
