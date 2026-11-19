-- Products waiting to be sent to a sales channel that follows the shop (settings.autoPublish on the account).
-- One row per (channel account, product): a product edited many times is sent once, a short while after the last edit.
CREATE TABLE integrations.auto_queue (
    account_id uuid        NOT NULL REFERENCES integrations.accounts (id) ON DELETE CASCADE,
    product_id uuid        NOT NULL,
    tenant_id  uuid        NOT NULL,
    queued_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, product_id)
);
CREATE INDEX idx_auto_queue_due ON integrations.auto_queue (queued_at);
