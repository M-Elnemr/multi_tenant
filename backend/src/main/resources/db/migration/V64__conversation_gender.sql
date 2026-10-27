-- The gender the customer stated in the chat («أنا راجل مش بنت»): it wins over records and guesses when the assistant addresses them.
ALTER TABLE chat.conversations ADD COLUMN gender VARCHAR(6);
