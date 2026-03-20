package com.zzhy.yg_ai.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 检验项目信息实体
 */
@Data
@TableName("items_infor_zhq")
public class ItemsInforZhq implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 请求号 (主键)
     */
    @TableId
    private String reqno;

    /**
     * 患者类型
     */
    private String pattype;

    /**
     * 数据名称 (主键)
     */
    private String dataname;

    /**
     * 时间 (主键)
     */
    @TableField("Ttime")
    private LocalDateTime ttime;

    /**
     * 样本请求号 (主键)
     */
    @TableField("SamReqno")
    private String samReqno;

    /**
     * 订单号
     */
    private Integer orderno;

    /**
     * 患者编号
     */
    private String patno;

    /**
     * 患者姓名
     */
    @TableField("Patname")
    private String patname;

    /**
     * 性别名称
     */
    private String sexname;

    /**
     * 科室名称
     */
    private String deptname;

    /**
     * 字典名称
     */
    private String dictname;

    /**
     * 床号
     */
    private String bedno;

    /**
     * 入院时间
     */
    @TableField("inHosdate")
    private LocalDateTime inHosdate;

    /**
     * 状态
     */
    @TableField("Stat")
    private String stat;

    /**
     * 检验结果
     */
    @TableField("TestResult")
    private String testResult;

    /**
     * 项目名称
     */
    private String itemname;

    /**
     * 目标集名称
     */
    @TableField("TargetSetName")
    private String targetSetName;

    /**
     * 项目 ID
     */
    private String itemid;

    /**
     * 数据编码
     */
    @TableField("data_code")
    private String dataCode;

    /**
     * 提示
     */
    private String msm;

    /**
     * 执行时间
     */
    private LocalDateTime exedate;

    /**
     * 状态
     */
    private String status;

    /**
     * 显示标志
     */
    private Integer showflag;

    /**
     * 指导
     */
    private String zhidao;

    /**
     * 其他
     */
    private String other;

    /**
     * 审核者
     */
    private String checker;

    /**
     * 审核时间
     */
    private LocalDateTime chedate;

    /**
     * 通过者
     */
    private String passer;

    /**
     * 通过时间
     */
    private LocalDateTime passdate;

    /**
     * 字典编号
     */
    private String dictno;

    /**
     * 就诊 ID
     */
    @TableField("visit_id")
    private String visitId;

    /**
     * 发送提示
     */
    private String sendmsm;

    /**
     * 忽略临床
     */
    @TableField("hulue_lc")
    private String hulueLc;

    /**
     * 短信
     */
    private String duanxin;

    /**
     * 医保
     */
    private String yibao;

    /**
     * 风险等级
     */
    @TableField("risk_level")
    private String riskLevel;

    /**
     * 综合征类型
     */
    @TableField("syndrome_type")
    private String syndromeType;

    /**
     * 分析推理
     */
    @TableField("analysis_reasoning")
    private String analysisReasoning;

    /**
     * 关键证据
     */
    @TableField("key_evidence")
    private String keyEvidence;

    /**
     * 建议措施
     */
    @TableField("recommended_actions")
    private String recommendedActions;
}
