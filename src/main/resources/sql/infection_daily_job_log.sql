IF OBJECT_ID('dbo.infection_daily_job_log', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.infection_daily_job_log (
        id BIGINT IDENTITY(1,1) PRIMARY KEY,
        job_date DATE NOT NULL,
        reqno VARCHAR(64) NULL,
        stage VARCHAR(32) NOT NULL,
        status VARCHAR(32) NOT NULL,
        message VARCHAR(2000) NULL,
        create_time DATETIME NOT NULL DEFAULT GETDATE()
    );

    CREATE INDEX idx_infection_daily_job_log_date_stage ON dbo.infection_daily_job_log(job_date, stage);
    CREATE INDEX idx_infection_daily_job_log_reqno ON dbo.infection_daily_job_log(reqno);
END
GO
