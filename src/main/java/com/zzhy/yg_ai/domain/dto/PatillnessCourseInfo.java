package com.zzhy.yg_ai.domain.dto;

import com.zzhy.yg_ai.domain.base.BaseDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Data
@EqualsAndHashCode(callSuper = true)
public class PatillnessCourseInfo extends BaseDTO {
    /**
     * 患者 ID
     */
    private String reqno;
    /**
     * 患者 ID
     */
    private String pathosid;
    /**
     * 患者姓名
     */
    private String patname;
    /**
     * 入院时间
     */
    private LocalDateTime inhosdate;
    /**
     * 科室 ID
     */
    private String currentdist;
    /**
     * 科室名称
     */
    private String displayname;

    /**
     * 患者类型
     */
    private String pattype;

    private String patno;

    private String sex;

    /**
     * 诊断信息列表
     */
    private List<DiagInfo> diagInfoList;

    /**
     * 病程信息列表
     */
    private List<IllnessInfo> illnessInfoList;

    /**
     * 获取合并后的病程内容（用于症候群监测等场景）
     * 将 illnessInfoList 中的 illnessContents 用分号拼接
     */
    public String getIllnessContents() {
        if (illnessInfoList == null || illnessInfoList.isEmpty()) {
            return "";
        }
        return illnessInfoList.stream()
                .map(IllnessInfo::getIllnessContents)
                .filter(c -> c != null && !c.isEmpty())
                .collect(Collectors.joining(";"));
    }

    /**
     * 构建症候群监测所需的完整内容，动态拼接诊断、病程内容、病程诊断
     */
    public String buildSurveillanceContent() {
        StringBuilder sb = new StringBuilder();

        // 诊断信息
        if (diagInfoList != null && !diagInfoList.isEmpty()) {
            String diagNames = diagInfoList.stream()
                    .map(DiagInfo::getDiagName)
                    .filter(d -> d != null && !d.trim().isEmpty())
                    .collect(Collectors.joining("、"));
            if (!diagNames.isEmpty()) {
                sb.append("【诊断信息】\n").append(diagNames).append("\n\n");
            }
        }

        // 病程内容
        String illnessContents = getIllnessContents();
        if (!illnessContents.isEmpty()) {
            sb.append("【病程内容】\n").append(illnessContents).append("\n\n");
        }

        // 病程诊断（itemNames）
        if (illnessInfoList != null && !illnessInfoList.isEmpty()) {
            String itemNames = illnessInfoList.stream()
                    .map(IllnessInfo::getItemNames)
                    .filter(n -> n != null && !n.trim().isEmpty())
                    .collect(Collectors.joining("；"));
            if (!itemNames.isEmpty()) {
                sb.append("【病程诊断】\n").append(itemNames);
            }
        }

        return sb.toString().trim();
    }

    @Data
    public static class DiagInfo {
        /**
         * 诊断名称
         */
        private String diagName;
    }

    @Data
    public static class IllnessInfo {
        /**
         * 病程内容
         */
        private String illnessContents;
        /**
         * 病程诊断
         */
        private String itemNames;
    }
}
