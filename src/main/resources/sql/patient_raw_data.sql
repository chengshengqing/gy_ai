IF COL_LENGTH('dbo.patient_raw_data', 'is_del') IS NULL
BEGIN
    ALTER TABLE dbo.patient_raw_data ADD is_del INT NULL;
END

IF NOT EXISTS (
    SELECT 1
    FROM sys.default_constraints dc
    INNER JOIN sys.columns c
        ON c.default_object_id = dc.object_id
    INNER JOIN sys.tables t
        ON t.object_id = c.object_id
    WHERE t.name = 'patient_raw_data'
      AND c.name = 'is_del'
)
BEGIN
    ALTER TABLE dbo.patient_raw_data
        ADD CONSTRAINT DF_patient_raw_data_is_del DEFAULT 0 FOR is_del;
END

UPDATE dbo.patient_raw_data
SET is_del = 0
WHERE is_del IS NULL;

ALTER TABLE dbo.patient_raw_data
ALTER COLUMN is_del INT NOT NULL;
