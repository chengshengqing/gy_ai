IF OBJECT_ID('dbo.patient_raw_data_change_task', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.patient_raw_data_change_task (
        id BIGINT IDENTITY(1,1) PRIMARY KEY,
        patient_raw_data_id BIGINT NOT NULL,
        reqno VARCHAR(64) NOT NULL,
        data_date DATE NULL,
        status VARCHAR(32) NOT NULL DEFAULT 'STRUCT_PENDING',
        attempt_count INT NOT NULL DEFAULT 0,
        max_attempts INT NOT NULL DEFAULT 5,
        raw_data_last_time DATETIME NOT NULL,
        source_batch_time DATETIME NULL,
        available_at DATETIME NOT NULL DEFAULT GETDATE(),
        last_start_time DATETIME NULL,
        last_finish_time DATETIME NULL,
        last_error_message VARCHAR(2000) NULL,
        create_time DATETIME NOT NULL DEFAULT GETDATE(),
        update_time DATETIME NOT NULL DEFAULT GETDATE()
    );

    CREATE UNIQUE INDEX uk_patient_raw_data_change_task_raw_data_ver
        ON dbo.patient_raw_data_change_task(patient_raw_data_id, raw_data_last_time);
    CREATE INDEX idx_patient_raw_data_change_task_status_available
        ON dbo.patient_raw_data_change_task(status, available_at, create_time);
    CREATE INDEX idx_patient_raw_data_change_task_reqno_status
        ON dbo.patient_raw_data_change_task(reqno, status, available_at);
END
GO

IF COL_LENGTH('dbo.patient_raw_data_change_task', 'data_date') IS NULL
BEGIN
    ALTER TABLE dbo.patient_raw_data_change_task ADD data_date DATE NULL;
END
GO

IF COL_LENGTH('dbo.patient_raw_data_change_task', 'status') IS NULL
BEGIN
    ALTER TABLE dbo.patient_raw_data_change_task ADD status VARCHAR(32) NOT NULL DEFAULT 'STRUCT_PENDING';
END
GO

IF COL_LENGTH('dbo.patient_raw_data_change_task', 'attempt_count') IS NULL
BEGIN
    ALTER TABLE dbo.patient_raw_data_change_task ADD attempt_count INT NOT NULL DEFAULT 0;
END
GO

IF COL_LENGTH('dbo.patient_raw_data_change_task', 'max_attempts') IS NULL
BEGIN
    ALTER TABLE dbo.patient_raw_data_change_task ADD max_attempts INT NOT NULL DEFAULT 5;
END
GO

IF COL_LENGTH('dbo.patient_raw_data_change_task', 'available_at') IS NULL
BEGIN
    ALTER TABLE dbo.patient_raw_data_change_task ADD available_at DATETIME NOT NULL DEFAULT GETDATE();
END
GO

IF COL_LENGTH('dbo.patient_raw_data_change_task', 'last_start_time') IS NULL
BEGIN
    ALTER TABLE dbo.patient_raw_data_change_task ADD last_start_time DATETIME NULL;
END
GO

IF COL_LENGTH('dbo.patient_raw_data_change_task', 'last_finish_time') IS NULL
BEGIN
    ALTER TABLE dbo.patient_raw_data_change_task ADD last_finish_time DATETIME NULL;
END
GO

IF COL_LENGTH('dbo.patient_raw_data_change_task', 'last_error_message') IS NULL
BEGIN
    ALTER TABLE dbo.patient_raw_data_change_task ADD last_error_message VARCHAR(2000) NULL;
END
GO

IF COL_LENGTH('dbo.patient_raw_data_change_task', 'update_time') IS NULL
BEGIN
    ALTER TABLE dbo.patient_raw_data_change_task ADD update_time DATETIME NOT NULL DEFAULT GETDATE();
END
GO

IF COL_LENGTH('dbo.patient_raw_data_change_task', 'source_batch_time') IS NULL
BEGIN
    ALTER TABLE dbo.patient_raw_data_change_task ADD source_batch_time DATETIME NULL;
END
GO
