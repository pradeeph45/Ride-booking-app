// Minimal typings for Google Identity Services (https://accounts.google.com/gsi/client).
interface GoogleCredentialResponse {
  credential: string;
}

interface GoogleAccountsId {
  initialize(config: { client_id: string; callback: (response: GoogleCredentialResponse) => void }): void;
  renderButton(
    parent: HTMLElement,
    options: { theme?: 'outline' | 'filled_blue' | 'filled_black'; size?: 'large' | 'medium' | 'small'; width?: number; text?: string },
  ): void;
}

interface Window {
  google?: { accounts: { id: GoogleAccountsId } };
}

interface ImportMetaEnv {
  /** Public OAuth client id from Google Cloud Console. Not a secret. Leave unset to hide the button. */
  readonly VITE_GOOGLE_CLIENT_ID?: string;
}
