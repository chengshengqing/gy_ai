package com.zzhy.yg_ai.domain.dto;

import com.zzhy.yg_ai.domain.base.BaseResult;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 感染分析结果
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class InfectionAnalysisResult extends BaseResult<InfectionAnalysisResult.InfectionData> {

    private static final long serialVersionUID = 1L;

    /**
     * 分析 ID
     */
    private Long analysisId;

    /**
     * 分析时间
     */
    private LocalDateTime analysisTime;

    /**
     * 感染风险评估
     */
    private String riskLevel;

    /**
     * 建议措施
     */
    private List<String> suggestions;

    /**
     * 内部静态类 - 实际的数据结构
     */
    @Data
    public static class InfectionData {
        /**
         * 患者 ID
         */
        private Long patientId;

        /**
         * 患者姓名
         */
        private String patientName;

        /**
         * 感染指标
         */
        private Double infectionIndex;

        /**
         * 白细胞计数
         */
        private Double wbcCount;

        /**
         * C 反应蛋白
         */
        private Double crpLevel;

        /**
         * 降钙素原
         */
        private Double pctLevel;

        /**
         * 分析结论
         */
        private String conclusion;
    }
}
