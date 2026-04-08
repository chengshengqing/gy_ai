IF OBJECT_ID('dbo.infection_case_snapshot', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.infection_case_snapshot (
        id BIGINT IDENTITY(1,1) PRIMARY KEY,
        reqno VARCHAR(64) NOT NULL,
        case_state VARCHAR(32) NOT NULL DEFAULT 'no_risk',
        warning_level VARCHAR(16) NOT NULL DEFAULT 'none',
        primary_site VARCHAR(32) NULL DEFAULT 'unknown',
        nosocomial_likelihood VARCHAR(16) NOT NULL DEFAULT 'low',
        current_new_onset_flag BIT NULL,
        current_after_48h_flag VARCHAR(16) NULL,
        current_procedure_related_flag BIT NULL,
        current_device_related_flag BIT NULL,
        current_infection_polarity VARCHAR(16) NULL,
        active_event_keys_json VARCHAR(MAX) NULL,
        active_risk_keys_json VARCHAR(MAX) NULL,
        active_against_keys_json VARCHAR(MAX) NULL,
        last_judge_time DATETIME NULL,
        last_result_version INT NOT NULL DEFAULT 0,
        last_event_pool_version BIGINT NOT NULL DEFAULT 0,
        last_candidate_since DATETIME NULL,
        last_warning_since DATETIME NULL,
        judge_debounce_until DATETIME NULL,
        created_at DATETIME NOT NULL DEFAULT GETDATE(),
        updated_at DATETIME NOT NULL DEFAULT GETDATE()
    );

    CREATE UNIQUE INDEX uk_infection_case_snapshot_reqno
        ON dbo.infection_case_snapshot(reqno);
END
GO
