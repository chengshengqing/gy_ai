IF OBJECT_ID('dbo.patient_raw_data_collect_task', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.patient_raw_data_collect_task (
        id BIGINT IDENTITY(1,1) PRIMARY KEY,
        reqno VARCHAR(64) NOT NULL,
        status VARCHAR(32) NOT NULL,
        attempt_count INT NOT NULL DEFAULT 0,
        max_attempts INT NOT NULL DEFAULT 5,
        previous_source_last_time DATETIME NULL,
        source_last_time DATETIME NULL,
        change_types VARCHAR(256) NULL,
        available_at DATETIME NOT NULL DEFAULT GETDATE(),
        last_start_time DATETIME NULL,
        last_finish_time DATETIME NULL,
        last_error_message VARCHAR(2000) NULL,
        create_time DATETIME NOT NULL DEFAULT GETDATE(),
        update_time DATETIME NOT NULL DEFAULT GETDATE()
    );

    CREATE UNIQUE INDEX uk_patient_raw_data_collect_task_reqno ON dbo.patient_raw_data_collect_task(reqno);
    CREATE INDEX idx_patient_raw_data_collect_task_status_available
        ON dbo.patient_raw_data_collect_task(status, available_at);
END
GO

IF COL_LENGTH('dbo.patient_raw_data_collect_task', 'change_types') IS NULL
BEGIN
    ALTER TABLE dbo.patient_raw_data_collect_task
        ADD change_types VARCHAR(256) NULL;
END
GO

IF COL_LENGTH('dbo.patient_raw_data_collect_task', 'previous_source_last_time') IS NULL
BEGIN
    ALTER TABLE dbo.patient_raw_data_collect_task
        ADD previous_source_last_time DATETIME NULL;
END
GO
