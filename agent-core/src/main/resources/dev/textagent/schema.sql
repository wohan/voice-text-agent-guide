CREATE TABLE agent_sessions (
 id VARCHAR(36) PRIMARY KEY, owner_hash VARCHAR(64) NOT NULL, revision BIGINT NOT NULL,
 expires_at BIGINT NOT NULL, payload TEXT NOT NULL
);
CREATE INDEX agent_sessions_owner ON agent_sessions(owner_hash);
CREATE INDEX agent_sessions_expiry ON agent_sessions(expires_at);
CREATE TABLE agent_audit (
 id VARCHAR(36) PRIMARY KEY, session_id VARCHAR(36) NOT NULL, owner_hash VARCHAR(64) NOT NULL,
 event_time BIGINT NOT NULL, phase VARCHAR(40) NOT NULL, operation_id VARCHAR(200), confirmation_id VARCHAR(36), http_status INTEGER
);
CREATE INDEX agent_audit_time ON agent_audit(event_time);
CREATE TABLE agent_rate_limits (owner_hash VARCHAR(64) PRIMARY KEY, bucket BIGINT NOT NULL, requests INTEGER NOT NULL);
CREATE TABLE agent_admission (id INTEGER PRIMARY KEY);
INSERT INTO agent_admission(id) VALUES (1);
