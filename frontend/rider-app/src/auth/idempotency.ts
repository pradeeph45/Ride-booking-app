/**
 * Remembers the Idempotency-Key for the booking the user is currently submitting.
 *
 * Same request again (double-click, flaky network retry, "try again" after an error) -> same key, so the
 * server returns the booking it already made instead of making a second one.
 * Different request (user changed vehicle or route) -> new key, because it is a genuinely different booking.
 * Call {@link clear} after a success so booking the same trip again later is a new booking.
 */
export class RetryKey {
  private fingerprint: string | undefined;
  private key: string | undefined;

  keyFor(fingerprint: string): string {
    if (fingerprint !== this.fingerprint || !this.key) {
      this.fingerprint = fingerprint;
      this.key = crypto.randomUUID(); // 36 chars of hex and dashes: valid for the server's key format
    }
    return this.key;
  }

  clear(): void {
    this.fingerprint = undefined;
    this.key = undefined;
  }
}
