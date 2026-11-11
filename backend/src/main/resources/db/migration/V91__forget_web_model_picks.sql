-- The website chat's model dropdown is gone: the model of a website chat is the platform admin's choice (AI configuration screen). Forget the picks visitors made while it was there.
UPDATE chat.conversations SET ai_model = NULL WHERE channel = 'WEB' AND ai_model IS NOT NULL;
