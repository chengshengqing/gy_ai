package com.zzhy.yg_ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.PropertySource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "timeline-view.rules")
@PropertySource(value = "classpath:timeline-view-rules.yaml", factory = YamlPropertySourceFactory.class)
public class TimelineViewRuleProperties {

    private List<String> primaryStatuses = new ArrayList<>(Arrays.asList(
            "active", "acute_exacerbation", "worsening", "improving"
    ));

    private List<String> keyDaySourceBadges = new ArrayList<>(Arrays.asList(
            "入院日", "手术日", "会诊", "拟出院"
    ));

    private List<String> highBadges = new ArrayList<>(Arrays.asList(
            "手术日", "高风险"
    ));

    private List<String> highPostOpBadgePrefixes = new ArrayList<>(List.of("术后第1天"));

    private List<String> mediumBadges = new ArrayList<>(Arrays.asList(
            "会诊"
    ));

    private List<String> sourceSurgeryPatterns = new ArrayList<>(List.of("手术记录"));
    private List<String> sourceConsultPatterns = new ArrayList<>(List.of("会诊记录|请会诊记录"));
    private List<String> sourceDischargePatterns = new ArrayList<>(List.of("出院记录"));
    private List<String> sourceAdmissionPatterns = new ArrayList<>(List.of("首次病程记录|入院记录"));

    private List<String> admissionSummaryPatterns = new ArrayList<>(List.of("入院|收住|初诊"));
    private List<String> dischargeSummaryPatterns = new ArrayList<>(List.of("拟办理出院|拟出院|出院"));

    private List<EnumLabelRule> hideProblemTypePatterns = new ArrayList<>();
    private List<String> hideProblemNamePatterns = new ArrayList<>(List.of("鉴别诊断"));
    private List<EnumLabelRule> importantUnconfirmedPatterns = new ArrayList<>();

    private List<PrimaryProblemBadgeRule> primaryProblemBadgeRules = new ArrayList<>();
    private List<EnumLabelRule> certaintyLabelRules = new ArrayList<>();
    private List<EnumLabelRule> statusLabelRules = new ArrayList<>();
    private List<EnumLabelRule> severityLabelRules = new ArrayList<>();
    private List<EnumLabelRule> problemTypeLabelRules = new ArrayList<>();

    private int maxBadges = 10;

    public TimelineViewRuleProperties() {
        primaryProblemBadgeRules.add(new PrimaryProblemBadgeRule("贫血", "贫血"));
        primaryProblemBadgeRules.add(new PrimaryProblemBadgeRule("感染", "感染"));
        primaryProblemBadgeRules.add(new PrimaryProblemBadgeRule("血栓|抗凝", "血栓风险"));
        primaryProblemBadgeRules.add(new PrimaryProblemBadgeRule("疼痛", "疼痛"));

        hideProblemTypePatterns.add(new EnumLabelRule("differential", "鉴别诊断"));

        importantUnconfirmedPatterns.add(new EnumLabelRule("suspected", "疑似"));
        importantUnconfirmedPatterns.add(new EnumLabelRule("possible", "可能"));
        importantUnconfirmedPatterns.add(new EnumLabelRule("workup_needed", "待排/待查"));
        importantUnconfirmedPatterns.add(new EnumLabelRule("risk_only", "风险提示"));

        certaintyLabelRules.add(new EnumLabelRule("confirmed", "明确"));
        certaintyLabelRules.add(new EnumLabelRule("suspected", "疑似"));
        certaintyLabelRules.add(new EnumLabelRule("possible", "可能"));
        certaintyLabelRules.add(new EnumLabelRule("workup_needed", "待排/待查"));
        certaintyLabelRules.add(new EnumLabelRule("risk_only", "风险提示"));

        statusLabelRules.add(new EnumLabelRule("active", "活动期"));
        statusLabelRules.add(new EnumLabelRule("acute_exacerbation", "急性加重"));
        statusLabelRules.add(new EnumLabelRule("worsening", "恶化"));
        statusLabelRules.add(new EnumLabelRule("improving", "好转"));
        statusLabelRules.add(new EnumLabelRule("chronic", "慢性"));
        statusLabelRules.add(new EnumLabelRule("stable", "稳定"));
        statusLabelRules.add(new EnumLabelRule("clarified", "明确"));
        statusLabelRules.add(new EnumLabelRule("unclear", "未明"));

        severityLabelRules.add(new EnumLabelRule("high", "高"));
        severityLabelRules.add(new EnumLabelRule("medium", "中"));
        severityLabelRules.add(new EnumLabelRule("low", "低"));

        problemTypeLabelRules.add(new EnumLabelRule("disease", "疾病"));
        problemTypeLabelRules.add(new EnumLabelRule("complication", "并发症/伴随问题"));
        problemTypeLabelRules.add(new EnumLabelRule("chronic", "慢性问题"));
        problemTypeLabelRules.add(new EnumLabelRule("risk_state", "风险状态"));
        problemTypeLabelRules.add(new EnumLabelRule("differential", "鉴别诊断"));
    }

    @Data
    public static class PrimaryProblemBadgeRule {
        private String pattern;
        private String badge;

        public PrimaryProblemBadgeRule() {
        }

        public PrimaryProblemBadgeRule(String pattern, String badge) {
            this.pattern = pattern;
            this.badge = badge;
        }
    }

    @Data
    public static class EnumLabelRule {
        private String value;
        private String label;

        public EnumLabelRule() {
        }

        public EnumLabelRule(String value, String label) {
            this.value = value;
            this.label = label;
        }
    }
}
