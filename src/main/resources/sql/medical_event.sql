-- =============================================
-- 医疗事件表 - SQL Server 建表脚本
-- 数据库：Microsoft SQL Server (版本 10.50.1600)
-- =============================================

-- 如果不存在则创建数据库
-- USE your_database_name;
-- GO

-- 删除已存在的表
IF EXISTS (SELECT * FROM sys.objects WHERE object_id = OBJECT_ID(N'[dbo].[medical_event]') AND type in (N'U'))
DROP TABLE [dbo].[medical_event];
GO

-- 创建医疗事件表
CREATE TABLE [dbo].[medical_event] (
    [id] BIGINT NOT NULL,
    [event_code] NVARCHAR(50) NULL,
    [event_name] NVARCHAR(200) NULL,
    [event_type] INT NULL,
    [patient_id] BIGINT NULL,
    [patient_name] NVARCHAR(100) NULL,
    [department_id] BIGINT NULL,
    [department_name] NVARCHAR(100) NULL,
    [doctor_id] BIGINT NULL,
    [doctor_name] NVARCHAR(100) NULL,
    [event_time] DATETIME NULL,
    [event_location] NVARCHAR(200) NULL,
    [create_time] DATETIME NULL,
    [update_time] DATETIME NULL,
    [create_by] NVARCHAR(50) NULL,
    [update_by] NVARCHAR(50) NULL,
    [is_deleted] INT DEFAULT 0 NULL,
    [remark] NVARCHAR(500) NULL,
    CONSTRAINT [PK_MEDICAL_EVENT] PRIMARY KEY CLUSTERED ([id])
);
GO

-- 添加扩展属性说明
EXEC sp_addextendedproperty 
    @name = N'MS_Description', 
    @value = N'医疗事件表',
    @level0type = N'SCHEMA', @level0name = N'dbo',
    @level1type = N'TABLE',  @level1name = N'medical_event';
GO

EXEC sp_addextendedproperty 
    @name = N'MS_Description', 
    @value = N'主键 ID',
    @level0type = N'SCHEMA', @level0name = N'dbo',
    @level1type = N'TABLE',  @level1name = N'medical_event',
    @level2type = N'COLUMN', @level2name = N'id';
GO

EXEC sp_addextendedproperty 
    @name = N'MS_Description', 
    @value = N'事件编号',
    @level0type = N'SCHEMA', @level0name = N'dbo',
    @level1type = N'TABLE',  @level1name = N'medical_event',
    @level2type = N'COLUMN', @level2name = N'event_code';
GO

EXEC sp_addextendedproperty 
    @name = N'MS_Description', 
    @value = N'事件名称',
    @level0type = N'SCHEMA', @level0name = N'dbo',
    @level1type = N'TABLE',  @level1name = N'medical_event',
    @level2type = N'COLUMN', @level2name = N'event_name';
GO

EXEC sp_addextendedproperty 
    @name = N'MS_Description', 
    @value = N'事件类型 (0-门诊，1-住院，2-急诊)',
    @level0type = N'SCHEMA', @level0name = N'dbo',
    @level1type = N'TABLE',  @level1name = N'medical_event',
    @level2type = N'COLUMN', @level2name = N'event_type';
GO

EXEC sp_addextendedproperty 
    @name = N'MS_Description', 
    @value = N'患者 ID',
    @level0type = N'SCHEMA', @level0name = N'dbo',
    @level1type = N'TABLE',  @level1name = N'medical_event',
    @level2type = N'COLUMN', @level2name = N'patient_id';
GO

EXEC sp_addextendedproperty 
    @name = N'MS_Description', 
    @value = N'患者姓名',
    @level0type = N'SCHEMA', @level0name = N'dbo',
    @level1type = N'TABLE',  @level1name = N'medical_event',
    @level2type = N'COLUMN', @level2name = N'patient_name';
GO

EXEC sp_addextendedproperty 
    @name = N'MS_Description', 
    @value = N'科室 ID',
    @level0type = N'SCHEMA', @level0name = N'dbo',
    @level1type = N'TABLE',  @level1name = N'medical_event',
    @level2type = N'COLUMN', @level2name = N'department_id';
GO

EXEC sp_addextendedproperty 
    @name = N'MS_Description', 
    @value = N'科室名称',
    @level0type = N'SCHEMA', @level0name = N'dbo',
    @level1type = N'TABLE',  @level1name = N'medical_event',
    @level2type = N'COLUMN', @level2name = N'department_name';
GO

EXEC sp_addextendedproperty 
    @name = N'MS_Description', 
    @value = N'医生 ID',
    @level0type = N'SCHEMA', @level0name = N'dbo',
    @level1type = N'TABLE',  @level1name = N'medical_event',
    @level2type = N'COLUMN', @level2name = N'doctor_id';
GO

EXEC sp_addextendedproperty 
    @name = N'MS_Description', 
    @value = N'医生姓名',
    @level0type = N'SCHEMA', @level0name = N'dbo',
    @level1type = N'TABLE',  @level1name = N'medical_event',
    @level2type = N'COLUMN', @level2name = N'doctor_name';
GO

EXEC sp_addextendedproperty 
    @name = N'MS_Description', 
    @value = N'事件发生时间',
    @level0type = N'SCHEMA', @level0name = N'dbo',
    @level1type = N'TABLE',  @level1name = N'medical_event',
    @level2type = N'COLUMN', @level2name = N'event_time';
GO

EXEC sp_addextendedproperty 
    @name = N'MS_Description', 
    @value = N'事件地点',
    @level0type = N'SCHEMA', @level0name = N'dbo',
    @level1type = N'TABLE',  @level1name = N'medical_event',
    @level2type = N'COLUMN', @level2name = N'event_location';
GO

EXEC sp_addextendedproperty 
    @name = N'MS_Description', 
    @value = N'创建时间',
    @level0type = N'SCHEMA', @level0name = N'dbo',
    @level1type = N'TABLE',  @level1name = N'medical_event',
    @level2type = N'COLUMN', @level2name = N'create_time';
GO

EXEC sp_addextendedproperty 
    @name = N'MS_Description', 
    @value = N'更新时间',
    @level0type = N'SCHEMA', @level0name = N'dbo',
    @level1type = N'TABLE',  @level1name = N'medical_event',
    @level2type = N'COLUMN', @level2name = N'update_time';
GO

EXEC sp_addextendedproperty 
    @name = N'MS_Description', 
    @value = N'创建人',
    @level0type = N'SCHEMA', @level0name = N'dbo',
    @level1type = N'TABLE',  @level1name = N'medical_event',
    @level2type = N'COLUMN', @level2name = N'create_by';
GO

EXEC sp_addextendedproperty 
    @name = N'MS_Description', 
    @value = N'更新人',
    @level0type = N'SCHEMA', @level0name = N'dbo',
    @level1type = N'TABLE',  @level1name = N'medical_event',
    @level2type = N'COLUMN', @level2name = N'update_by';
GO

EXEC sp_addextendedproperty 
    @name = N'MS_Description', 
    @value = N'是否删除 (0-未删除，1-已删除)',
    @level0type = N'SCHEMA', @level0name = N'dbo',
    @level1type = N'TABLE',  @level1name = N'medical_event',
    @level2type = N'COLUMN', @level2name = N'is_deleted';
GO

EXEC sp_addextendedproperty 
    @name = N'MS_Description', 
    @value = N'备注',
    @level0type = N'SCHEMA', @level0name = N'dbo',
    @level1type = N'TABLE',  @level1name = N'medical_event',
    @level2type = N'COLUMN', @level2name = N'remark';
GO

-- 创建索引
CREATE NONCLUSTERED INDEX [IX_patient_id] 
ON [dbo].[medical_event] ([patient_id]);
GO

CREATE NONCLUSTERED INDEX [IX_event_type] 
ON [dbo].[medical_event] ([event_type]);
GO

CREATE NONCLUSTERED INDEX [IX_event_time] 
ON [dbo].[medical_event] ([event_time]);
GO

-- 插入测试数据
INSERT INTO [dbo].[medical_event] (
    [id], [event_code], [event_name], [event_type], [patient_id], [patient_name],
    [department_id], [department_name], [doctor_id], [doctor_name],
    [event_time], [event_location], [create_time], [update_time], [is_deleted]
) VALUES (
    1, N'EV20260306001', N'门诊就诊', 0, 1001, N'张三',
    101, N'内科', 201, N'李医生',
    GETDATE(), N'门诊楼 1 层 101 室', GETDATE(), GETDATE(), 0
);
GO

INSERT INTO [dbo].[medical_event] (
    [id], [event_code], [event_name], [event_type], [patient_id], [patient_name],
    [department_id], [department_name], [doctor_id], [doctor_name],
    [event_time], [event_location], [create_time], [update_time], [is_deleted]
) VALUES (
    2, N'EV20260306002', N'急诊就诊', 2, 1002, N'李四',
    102, N'急诊科', 202, N'王医生',
    GETDATE(), N'急诊楼 2 层 201 室', GETDATE(), GETDATE(), 0
);
GO
