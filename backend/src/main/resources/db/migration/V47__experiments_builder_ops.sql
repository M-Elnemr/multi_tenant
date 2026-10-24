-- Wave 4: A/B tests on the announcement bar, AI store builder bookkeeping.

CREATE TABLE commerce.experiments (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID NOT NULL REFERENCES core.tenants(id) ON DELETE CASCADE,
    name       VARCHAR(120) NOT NULL,
    variant_a  VARCHAR(200) NOT NULL,
    variant_b  VARCHAR(200) NOT NULL,
    status     VARCHAR(8) NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING', 'STOPPED')),
    winner     CHAR(1) CHECK (winner IN ('A', 'B')),
    created_by UUID,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at   TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_one_running_experiment ON commerce.experiments (tenant_id) WHERE status = 'RUNNING';

CREATE TABLE commerce.experiment_events (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id UUID NOT NULL REFERENCES commerce.experiments(id) ON DELETE CASCADE,
    variant       CHAR(1) NOT NULL CHECK (variant IN ('A', 'B')),
    type          VARCHAR(5) NOT NULL CHECK (type IN ('view', 'order')),
    client_key    VARCHAR(80) NOT NULL,
    order_number  VARCHAR(40),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- a visitor counts once per experiment; an order counts once
CREATE UNIQUE INDEX uq_experiment_view ON commerce.experiment_events (experiment_id, client_key) WHERE type = 'view';
CREATE UNIQUE INDEX uq_experiment_order ON commerce.experiment_events (experiment_id, order_number) WHERE type = 'order';

INSERT INTO core.permissions (code, name) VALUES ('experiment.manage', 'Run A/B tests and use the AI store builder');
INSERT INTO core.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM core.roles r, core.permissions p
WHERE r.code IN ('STORE_OWNER', 'STORE_ADMIN') AND r.tenant_id IS NULL AND p.code = 'experiment.manage'
ON CONFLICT DO NOTHING;

-- one push per kind of problem per day
CREATE TABLE core.ops_alerts (
    kind VARCHAR(40) NOT NULL,
    day  DATE NOT NULL,
    PRIMARY KEY (kind, day)
);
