-- Small platform-level settings of the social networks (for X: the id of the webhook registered with X).
CREATE TABLE chat.social_config (
    platform VARCHAR(12) NOT NULL,
    key      VARCHAR(40) NOT NULL,
    value    TEXT NOT NULL,
    PRIMARY KEY (platform, key)
);
