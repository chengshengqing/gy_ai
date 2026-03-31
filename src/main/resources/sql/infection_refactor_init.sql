IF OBJECT_ID('dbo.infection_event_pool', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.infection_event_pool (
        id BIGINT IDENTITY(1,1) PRIMARY KEY,
        reqno VARCHAR(64) NOT NULL,
        raw_data_id BIGINT NULL,
        data_date DATE NULL,
        source_type VARCHAR(32) NOT NULL,
        source_ref VARCHAR(128) NULL,
        event_key VARCHAR(255) NOT NULL,
        event_type VARCHAR(64) NOT NULL,
        event_subtype VARCHAR(64) NULL,
        event_category VARCHAR(64) NULL,
        event_time DATETIME NULL,
        detected_time DATETIME NOT NULL,
        ingest_time DATETIME NOT NULL,
        site VARCHAR(64) NULL,
        polarity VARCHAR(32) NULL,
        certainty VARCHAR(32) NULL,
        severity VARCHAR(32) NULL,
        is_hard_fact BIT NULL,
        is_active BIT NULL,
        title VARCHAR(255) NULL,
        content VARCHAR(MAX) NULL,
        evidence_json VARCHAR(MAX) NULL,
        attributes_json VARCHAR(MAX) NULL,
        extractor_type VARCHAR(64) NULL,
        prompt_version VARCHAR(64) NULL,
        model_name VARCHAR(128) NULL,
        confidence DECIMAL(5,4) NULL,
        status VARCHAR(32) NOT NULL,
        created_at DATETIME NOT NULL DEFAULT GETDATE(),
        updated_at DATETIME NOT NULL DEFAULT GETDATE()
    );

    CREATE UNIQUE INDEX uk_infection_event_pool_event_key ON dbo.infection_event_pool(event_key);
    CREATE INDEX idx_infection_event_pool_reqno_time ON dbo.infection_event_pool(reqno, event_time);
END
GO

IF OBJECT_ID('dbo.infection_llm_node_run', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.infection_llm_node_run (
        id BIGINT IDENTITY(1,1) PRIMARY KEY,
        reqno VARCHAR(64) NOT NULL,
        raw_data_id BIGINT NULL,
        alert_result_id BIGINT NULL,
        node_run_key VARCHAR(255) NOT NULL,
        node_type VARCHAR(64) NOT NULL,
        node_name VARCHAR(128) NOT NULL,
        prompt_version VARCHAR(64) NULL,
        model_name VARCHAR(128) NULL,
        input_payload VARCHAR(MAX) NULL,
        output_payload VARCHAR(MAX) NULL,
        normalized_output_payload VARCHAR(MAX) NULL,
        status VARCHAR(32) NOT NULL,
        confidence DECIMAL(5,4) NULL,
        latency_ms BIGINT NULL,
        retry_count INT NOT NULL DEFAULT 0,
        error_code VARCHAR(64) NULL,
        error_message VARCHAR(2000) NULL,
        created_at DATETIME NOT NULL DEFAULT GETDATE(),
        updated_at DATETIME NOT NULL DEFAULT GETDATE()
    );

    CREATE UNIQUE INDEX uk_infection_llm_node_run_key ON dbo.infection_llm_node_run(node_run_key);
    CREATE INDEX idx_infection_llm_node_run_reqno_type ON dbo.infection_llm_node_run(reqno, node_type);
END
GO
