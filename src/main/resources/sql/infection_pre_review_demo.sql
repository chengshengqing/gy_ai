IF OBJECT_ID('dbo.infection_pre_review_demo', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.infection_pre_review_demo (
        reqno VARCHAR(64) NOT NULL PRIMARY KEY,
        timeline_html NVARCHAR(MAX) NULL,
        ai_pre_review_json NVARCHAR(MAX) NULL
    );
END
GO
