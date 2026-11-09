-- Round 5: Facebook followers are read page by page through the Page session; when a run stops half way (rate limit, stale query id) the next run continues from the last cursor.
ALTER TABLE commerce.prospect_syncs ADD COLUMN resume_cursor TEXT, ADD COLUMN resume_collection VARCHAR(300);
