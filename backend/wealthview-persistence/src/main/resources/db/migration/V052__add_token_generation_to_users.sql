-- Adds token generation counter for refresh token rotation.
-- Incrementing this value invalidates all existing refresh tokens for that user.
ALTER TABLE users ADD COLUMN token_generation integer NOT NULL DEFAULT 0;
