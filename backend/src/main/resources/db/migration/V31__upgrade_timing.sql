-- Upgrading while a paid period is running: either right now (the unused part of the current plan is credited) or from the next period.
ALTER TABLE billing.pay_requests ADD COLUMN timing VARCHAR(12) NOT NULL DEFAULT 'NOW' CHECK (timing IN ('NOW','NEXT_PERIOD'));
ALTER TABLE billing.pay_requests ADD COLUMN credit_minor BIGINT NOT NULL DEFAULT 0;
ALTER TABLE billing.pay_requests DROP CONSTRAINT pay_requests_status_check;
ALTER TABLE billing.pay_requests ADD CONSTRAINT pay_requests_status_check CHECK (status IN ('NEW','CONTACTED','PAID','REJECTED','SCHEDULED'));
ALTER TABLE billing.tenant_subscriptions ADD COLUMN pending_plan_id UUID REFERENCES billing.subscription_plans(id);
