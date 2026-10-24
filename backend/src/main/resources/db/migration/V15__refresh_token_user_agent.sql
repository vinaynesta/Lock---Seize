-- F9: remember which client each session belongs to, so a user can recognise their devices.
-- Truncated to 300 characters; it is client-supplied and only ever displayed back to its own user.
ALTER TABLE refresh_tokens ADD COLUMN user_agent VARCHAR(300);
