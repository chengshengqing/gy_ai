IF OBJECT_ID('dbo.infection_event_task', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.infection_event_task (
        id BIGINT IDENTITY(1,1) PRIMARY KEY,
        task_type VARCHAR(32) NOT NULL,
        status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
        reqno VARCHAR(64) NOT NULL,
        patient_raw_data_id BIGINT NOT NULL,
        data_date DATE NULL,
        raw_data_last_time DATETIME NOT NULL,
        source_batch_time DATETIME NOT NULL,
        changed_types VARCHAR(512) NULL,
        trigger_reason_codes VARCHAR(512) NULL,
        priority INT NOT NULL DEFAULT 100,
        merge_key VARCHAR(128) NOT NULL,
        first_triggered_at DATETIME NULL,
        last_event_at DATETIME NULL,
        debounce_until DATETIME NULL,
        trigger_priority VARCHAR(16) NULL,
        event_pool_version_at_enqueue BIGINT NULL,
        attempt_count INT NOT NULL DEFAULT 0,
        max_attempts INT NOT NULL DEFAULT 5,
        available_at DATETIME NOT NULL DEFAULT GETDATE(),
        last_start_time DATETIME NULL,
        last_finish_time DATETIME NULL,
        last_error_message VARCHAR(2000) NULL,
        create_time DATETIME NOT NULL DEFAULT GETDATE(),
        update_time DATETIME NOT NULL DEFAULT GETDATE()
    );

    CREATE UNIQUE INDEX uk_infection_event_task_merge_key_type
        ON dbo.infection_event_task(task_type, merge_key);
    CREATE INDEX idx_infection_event_task_pending
        ON dbo.infection_event_task(task_type, status, available_at);
    CREATE INDEX idx_infection_event_task_reqno
        ON dbo.infection_event_task(reqno, task_type, create_time);
END
GO
