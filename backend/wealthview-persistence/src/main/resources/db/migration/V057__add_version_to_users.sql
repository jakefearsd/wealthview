-- Add optimistic-lock version column to users so racing token refreshes
-- surface as OptimisticLockingFailureException instead of silently
-- clobbering the winner's generation bump.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;
