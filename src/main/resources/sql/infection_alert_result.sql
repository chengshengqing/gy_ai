IF OBJECT_ID('dbo.infection_alert_result', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.infection_alert_result (
        id BIGINT IDENTITY(1,1) PRIMARY KEY,
        reqno VARCHAR(64) NOT NULL,
        data_date DATE NULL,
        result_version INT NOT NULL,
        alert_status VARCHAR(32) NOT NULL,
        overall_risk_level VARCHAR(16) NOT NULL,
        primary_site VARCHAR(32) NULL,
        new_onset_flag BIT NULL,
        after_48h_flag VARCHAR(16) NULL,
        procedure_related_flag BIT NULL,
        device_related_flag BIT NULL,
        infection_polarity VARCHAR(16) NULL,
        result_json VARCHAR(MAX) NULL,
        diff_json VARCHAR(MAX) NULL,
        source_snapshot_id BIGINT NULL,
        create_time DATETIME NOT NULL DEFAULT GETDATE()
    );

    CREATE INDEX idx_infection_alert_result_reqno_ver
        ON dbo.infection_alert_result(reqno, result_version);
END
GO
