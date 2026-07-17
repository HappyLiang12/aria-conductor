-- V13: Aria session persistence for backend restart survival (Issue #14).
-- Conversation history is stored in DB so it survives JVM restarts.
-- MariaDB-compatible syntax (TEXT instead of TEXT).

CREATE TABLE aria_sessions (
    session_id VARCHAR(36) PRIMARY KEY,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

CREATE TABLE aria_messages (
    id VARCHAR(36) PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    FOREIGN KEY (session_id) REFERENCES aria_sessions(session_id) ON DELETE CASCADE
);

CREATE INDEX idx_aria_msg_session ON aria_messages(session_id);
CREATE INDEX idx_aria_msg_created ON aria_messages(created_at);
