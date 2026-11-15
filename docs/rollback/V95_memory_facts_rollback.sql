-- Rolls back V95__memory_facts.sql (run by hand; Flyway does not undo). Loses only the structured memory facts; messages and the free-text customer notes are untouched.
DROP TABLE IF EXISTS chat.memory_facts;
-- then: DELETE FROM flyway_schema_history WHERE version = '95';
