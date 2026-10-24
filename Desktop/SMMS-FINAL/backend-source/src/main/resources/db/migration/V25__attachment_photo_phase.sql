-- V25: before/after photo phase on attachments (NULL = general attachment).
ALTER TABLE attachments
  ADD COLUMN IF NOT EXISTS photo_phase VARCHAR(10) CHECK (photo_phase IN ('before', 'after'));
