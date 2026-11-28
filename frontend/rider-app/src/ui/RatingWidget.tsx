import { useEffect, useState, type FormEvent } from 'react';
import { ApiError } from '../auth/api';
import { getMyRating, getMyRatingSummary, submitRating, type MyRating, type RatingSummary } from '../auth/ratings';
import { formatRating } from './format';

const WORDS = ['', 'Poor', 'Fair', 'Good', 'Very good', 'Excellent'];

function Stars({ value }: { value: number }) {
  return (
    <span className="stars" aria-hidden="true">
      {[1, 2, 3, 4, 5].map((n) => <span key={n} className={n <= value ? 'on' : ''}>★</span>)}
    </span>
  );
}

/**
 * Rate the other person on a finished trip. Shows what you already gave if you rated, otherwise five stars and an
 * optional comment. A rating is final, so it says so before you send it.
 */
export function RatingWidget({ bookingId, whom, onRated }: { bookingId: string; whom: 'driver' | 'rider'; onRated?: () => void }) {
  const [mine, setMine] = useState<MyRating | null | undefined>(undefined); // undefined = still loading
  const [score, setScore] = useState(0);
  const [comment, setComment] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    getMyRating(bookingId).then((r) => alive && setMine(r)).catch(() => alive && setMine(null));
    return () => { alive = false; };
  }, [bookingId]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (score === 0 || busy) return;
    setBusy(true);
    setError(null);
    try {
      setMine(await submitRating(bookingId, score, comment));
      onRated?.();
    } catch (err) {
      if (err instanceof ApiError && err.code === 'ALREADY_RATED') {
        setMine(await getMyRating(bookingId).catch(() => null)); // already rated elsewhere (another tab): show that one
      } else {
        setError('We could not save your rating. Please try again.');
      }
    } finally {
      setBusy(false);
    }
  }

  if (mine === undefined) return null;

  if (mine) {
    return (
      <div className="rating done" aria-label="Your rating">
        <Stars value={mine.score} />
        <span>You rated your {whom} {mine.score}/5{mine.comment ? ` · “${mine.comment}”` : ''}</span>
      </div>
    );
  }

  return (
    <form className="rating" onSubmit={submit} aria-label={`Rate your ${whom}`}>
      <strong>How was your {whom}?</strong>
      <div className="star-input" role="radiogroup" aria-label="Stars">
        {[1, 2, 3, 4, 5].map((n) => (
          <button key={n} type="button" role="radio" aria-checked={score === n} aria-label={`${n} star${n > 1 ? 's' : ''}`}
            className={n <= score ? 'on' : ''} onClick={() => setScore(n)}>★</button>
        ))}
        <span className="hint">{WORDS[score]}</span>
      </div>
      {score > 0 && (
        <>
          <textarea value={comment} maxLength={500} rows={2} placeholder="Add a comment (optional)" aria-label="Comment"
            onChange={(e) => setComment(e.target.value)} />
          <span className="hint">Ratings are final once sent.</span>
          <button className="btn sm" disabled={busy}>{busy ? 'Sending…' : 'Send rating'}</button>
        </>
      )}
      {error && <div role="alert" className="alert error">{error}</div>}
    </form>
  );
}

/** "★ 4.8 (12 ratings)" for the signed-in user, or nothing until someone has rated them. */
export function MyRatingBadge() {
  const [summary, setSummary] = useState<RatingSummary | null>(null);
  useEffect(() => {
    getMyRatingSummary().then(setSummary).catch(() => setSummary(null));
  }, []);
  const text = summary ? formatRating(summary.average, summary.count) : null;
  return text ? <span className="pill ok" title="How others rate you">{text}</span> : null;
}
