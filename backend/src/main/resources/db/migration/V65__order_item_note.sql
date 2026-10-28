-- A note on each line of an order («بدون بصل», «حار جداً», «مقاس أكبر شوية»), from the website, the AI chat or the till.
ALTER TABLE commerce.order_items ADD COLUMN note VARCHAR(200);
