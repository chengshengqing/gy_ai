-- =============================================
-- SQL Server 2012 建表语句
-- 对应实体: InfectionAlertEntity, InfectionReviewEntity, PatientRawDataEntity, PatientSummaryEntity
-- =============================================

-- 1. 感染报警表 infection_alert
IF NOT EXISTS (SELECT * FROM sys.objects WHERE object_id = OBJECT_ID(N'[dbo].[infection_alert]') AND type in (N'U'))
BEGIN
    CREATE TABLE [dbo].[infection_alert] (
        [id] BIGINT IDENTITY(1,1) NOT NULL,
        [reqno] NVARCHAR(100) NULL,
        [risk_level] NVARCHAR(50) NULL,
        [infection_type] NVARCHAR(100) NULL,
        [evidence] NVARCHAR(MAX) NULL,
        [alert_time] DATETIME2 NULL,
        [status] NVARCHAR(50) NULL,
        CONSTRAINT [PK_infection_alert] PRIMARY KEY CLUSTERED ([id] ASC)
    );
END
GO

-- 2. 感染审核表 infection_review
IF NOT EXISTS (SELECT * FROM sys.objects WHERE object_id = OBJECT_ID(N'[dbo].[infection_review]') AND type in (N'U'))
BEGIN
    CREATE TABLE [dbo].[infection_review] (
        [id] BIGINT IDENTITY(1,1) NOT NULL,
        [alert_id] BIGINT NULL,
        [reqno] NVARCHAR(100) NULL,
        [final_alert] BIT NULL,
        [confidence] FLOAT NULL,
        [review_comment] NVARCHAR(MAX) NULL,
        [create_time] DATETIME2 NULL,
        CONSTRAINT [PK_infection_review] PRIMARY KEY CLUSTERED ([id] ASC)
    );
END
GO

-- 3. 患者原始数据表 patient_raw_data
IF NOT EXISTS (SELECT * FROM sys.objects WHERE object_id = OBJECT_ID(N'[dbo].[patient_raw_data]') AND type in (N'U'))
BEGIN
    CREATE TABLE [dbo].[patient_raw_data] (
        [id] BIGINT IDENTITY(1,1) NOT NULL,
        [reqno] NVARCHAR(100) NULL,
        [data_json] NVARCHAR(MAX) NULL,
        [struct_data_json] NVARCHAR(MAX) NULL,
        [create_time] DATETIME2 NULL,
        [last_time] DATETIME2 NULL,
        CONSTRAINT [PK_patient_raw_data] PRIMARY KEY CLUSTERED ([id] ASC)
    );
END
GO

IF COL_LENGTH('dbo.patient_raw_data', 'struct_data_json') IS NULL
BEGIN
    ALTER TABLE [dbo].[patient_raw_data] ADD [struct_data_json] NVARCHAR(MAX) NULL;
END
GO

-- 4. 患者摘要表 patient_summary
IF NOT EXISTS (SELECT * FROM sys.objects WHERE object_id = OBJECT_ID(N'[dbo].[patient_summary]') AND type in (N'U'))
BEGIN
    CREATE TABLE [dbo].[patient_summary] (
        [id] BIGINT IDENTITY(1,1) NOT NULL,
        [reqno] NVARCHAR(100) NULL,
        [summary_json] NVARCHAR(MAX) NULL,
        [token_count] INT NULL,
        [update_time] DATETIME2 NULL,
        CONSTRAINT [PK_patient_summary] PRIMARY KEY CLUSTERED ([id] ASC)
    );
END
GO

-- 可选: 为常用查询字段创建索引（已包含存在性检查）
IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'IX_infection_alert_reqno' AND object_id = OBJECT_ID('dbo.infection_alert'))
    CREATE NONCLUSTERED INDEX [IX_infection_alert_reqno] ON [dbo].[infection_alert]([reqno] ASC);
IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'IX_infection_alert_alert_time' AND object_id = OBJECT_ID('dbo.infection_alert'))
    CREATE NONCLUSTERED INDEX [IX_infection_alert_alert_time] ON [dbo].[infection_alert]([alert_time] ASC);

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'IX_infection_review_alert_id' AND object_id = OBJECT_ID('dbo.infection_review'))
    CREATE NONCLUSTERED INDEX [IX_infection_review_alert_id] ON [dbo].[infection_review]([alert_id] ASC);
IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'IX_infection_review_reqno' AND object_id = OBJECT_ID('dbo.infection_review'))
    CREATE NONCLUSTERED INDEX [IX_infection_review_reqno] ON [dbo].[infection_review]([reqno] ASC);

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'IX_patient_raw_data_reqno' AND object_id = OBJECT_ID('dbo.patient_raw_data'))
    CREATE NONCLUSTERED INDEX [IX_patient_raw_data_reqno] ON [dbo].[patient_raw_data]([reqno] ASC);

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'IX_patient_summary_reqno' AND object_id = OBJECT_ID('dbo.patient_summary'))
    CREATE NONCLUSTERED INDEX [IX_patient_summary_reqno] ON [dbo].[patient_summary]([reqno] ASC);
IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'IX_patient_summary_update_time' AND object_id = OBJECT_ID('dbo.patient_summary'))
    CREATE NONCLUSTERED INDEX [IX_patient_summary_update_time] ON [dbo].[patient_summary]([update_time] ASC);
