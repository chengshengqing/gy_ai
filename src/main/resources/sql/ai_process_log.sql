-- =============================================
-- AI 处理日志表 - MySQL 5.7 建表脚本
-- 数据库：MySQL 5.7+
-- =============================================

-- 如果不存在则创建数据库
-- CREATE DATABASE IF NOT EXISTS yg_ai DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- USE yg_ai;

-- 删除已存在的表
DROP TABLE IF EXISTS `ai_process_log`;

-- 创建 AI 处理日志表
CREATE TABLE `ai_process_log` (
  `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '主键 ID（自增）',
  `reqno` VARCHAR(100) DEFAULT NULL COMMENT '请求号',
  `pathosid` VARCHAR(100) DEFAULT NULL COMMENT '患者 ID',
  `record_id` BIGINT(20) DEFAULT NULL COMMENT '原始记录 ID',
  `process_type` VARCHAR(50) NOT NULL COMMENT '处理类型：STRUCT_CLASSIFICATION-结构化分类、EVENT_EXTRACTION-事件抽取等',
  `status` VARCHAR(20) NOT NULL COMMENT '处理状态：SUCCESS-成功、FAILED-失败',
  `ai_response` LONGTEXT COMMENT 'AI 返回的原始响应内容',
  `error_code` VARCHAR(50) DEFAULT NULL COMMENT '错误码（失败时填写）',
  `error_message` VARCHAR(1000) DEFAULT NULL COMMENT '错误信息（失败时填写）',
  `extra_data` TEXT COMMENT '额外信息（JSON 格式，用于存储扩展数据）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_reqno` (`reqno`),
  KEY `idx_pathosid` (`pathosid`),
  KEY `idx_record_id` (`record_id`),
  KEY `idx_process_type` (`process_type`),
  KEY `idx_status` (`status`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 处理日志表';

-- 添加表注释
ALTER TABLE `ai_process_log` COMMENT = 'AI 处理日志表，用于记录 AI 模型调用过程中的成功和失败信息';

-- 示例数据（可选）
-- INSERT INTO `ai_process_log` (`process_type`, `status`, `ai_response`, `error_code`, `error_message`, `extra_data`) 
-- VALUES ('STRUCT_CLASSIFICATION', 'SUCCESS', '{\"test\": \"data\"}', NULL, NULL, '{\"recordId\": \"1\"}');
