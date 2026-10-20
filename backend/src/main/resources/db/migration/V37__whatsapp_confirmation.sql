-- Free re-confirmation of an order on WhatsApp: the customer's number and consent, and when they confirmed there ----
ALTER TABLE commerce.orders
    ADD COLUMN whatsapp_number       VARCHAR(30),
    ADD COLUMN whatsapp_opt_in_at    TIMESTAMPTZ,
    ADD COLUMN whatsapp_confirmed_at TIMESTAMPTZ;
ALTER TABLE commerce.customers
    ADD COLUMN whatsapp_number    VARCHAR(30),
    ADD COLUMN whatsapp_opt_in_at TIMESTAMPTZ;
-- The displayed number of a connected WhatsApp channel (used for the "Confirm on WhatsApp" link).
ALTER TABLE chat.channels ADD COLUMN phone VARCHAR(30);
