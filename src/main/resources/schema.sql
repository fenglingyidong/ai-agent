CREATE TABLE IF NOT EXISTS conversation_sessions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(128) NOT NULL,
    title VARCHAR(255) NOT NULL DEFAULT '',
    next_turn_no BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at DATETIME(3) NULL,
    UNIQUE KEY uk_conversation_sessions_user_session (user_id, session_id),
    KEY idx_conversation_sessions_user_updated (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS conversation_turns (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    turn_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(128) NOT NULL,
    turn_no BIGINT NOT NULL,
    model_id VARCHAR(128) NOT NULL DEFAULT '',
    web_search_enabled TINYINT(1) NOT NULL DEFAULT 0,
    user_text LONGTEXT NOT NULL,
    assistant_text LONGTEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    error_message TEXT NULL,
    metadata_json JSON NULL,
    created_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_conversation_turns_turn_id (turn_id),
    UNIQUE KEY uk_conversation_turns_user_session_turn (user_id, session_id, turn_no),
    KEY idx_conversation_turns_user_session_created (user_id, session_id, created_at),
    KEY idx_conversation_turns_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rag_parent_documents (
    parent_id VARCHAR(260) COLLATE utf8mb4_bin NOT NULL,
    source_id VARCHAR(160) COLLATE utf8mb4_bin NOT NULL,
    title VARCHAR(512) NOT NULL DEFAULT '',
    content MEDIUMTEXT NOT NULL,
    parent_index INT NOT NULL DEFAULT 0,
    document_hash CHAR(64) NOT NULL DEFAULT '',
    metadata_json JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (parent_id),
    KEY idx_rag_parent_documents_source_parent (source_id, parent_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
