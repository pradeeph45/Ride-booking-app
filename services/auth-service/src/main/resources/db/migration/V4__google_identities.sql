-- Has the user proven control of this email address? Existing rows are unverified (nobody checked).
ALTER TABLE users ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;

-- Links an external identity (e.g. a Google account) to one of our users.
-- (provider, subject) is globally unique: one Google account can never belong to two users.
CREATE TABLE user_identities (
    id         BINARY(16)   NOT NULL,
    user_id    BINARY(16)   NOT NULL,
    provider   VARCHAR(20)  NOT NULL,
    subject    VARCHAR(255) NOT NULL,   -- the provider's stable user id ("sub"); emails can change, this cannot
    email      VARCHAR(254) NOT NULL,   -- email the provider reported at link time (for audit only)
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_identity_provider_subject UNIQUE (provider, subject),
    CONSTRAINT fk_identity_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    INDEX idx_identity_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
