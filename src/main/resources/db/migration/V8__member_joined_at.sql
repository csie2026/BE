-- Keep legacy join dates unknown. Set a default only after adding the nullable column.
ALTER TABLE members ADD COLUMN joined_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE members ALTER COLUMN joined_at SET DEFAULT CURRENT_TIMESTAMP;
