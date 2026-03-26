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
            "会诊", "拟手术"
    ));

    private List<String> sourceSurgeryPatterns = new ArrayList<>(List.of("手术记录"));
    private List<String> sourceConsultPatterns = new ArrayList<>(List.of("会诊记录|请会诊记录"));
    private List<String> sourceDischargePatterns = new ArrayList<>(List.of("出院记录"));
    private List<String> sourceAdmissionPatterns = new ArrayList<>(List.of("首次病程记录"));

    private List<String> admissionSummaryPatterns = new ArrayList<>(List.of("入院|收住|初诊"));
    private List<String> dischargeSummaryPatterns = new ArrayList<>(List.of("拟办理出院|拟出院|出院"));

    private List<String> feverEvidencePatterns = new ArrayList<>(List.of("体温[^0-9]{0,8}3[8-9](\\.\\d+)?"));
    private List<String> feverTextPatterns = new ArrayList<>(List.of("发热|持续发热|体温最高"));
    private List<String> feverRiskPatterns = new ArrayList<>(List.of("感染"));
    private List<String> surgeryPlanningPatterns = new ArrayList<>(List.of("拟行|择期手术|置换术|手术审批|排期|评估手术可行性"));
    private List<String> postOpPatterns = new ArrayList<>(List.of("术后|术区|引流|切口"));
    private List<String> performedSurgeryPatterns = new ArrayList<>(List.of("(?<!拟)(已行|下行|行)[^。；]{0,15}(术|置换)"));
    private List<String> criticalRiskPatterns = new ArrayList<>(List.of("感染|出血|血栓|围术期心脏|呼吸衰竭|猝死"));

    private List<StatusLabelRule> hideProblemTypePatterns = new ArrayList<>();
    private List<String> hideProblemNamePatterns = new ArrayList<>(List.of("鉴别诊断"));
    private List<StatusLabelRule> importantUnconfirmedPatterns = new ArrayList<>();

    private List<String> treatmentPatterns = new ArrayList<>(List.of(
            "注射|静滴|静脉|口服|给药|用药|抗凝|抗感染|镇痛|保肝|治疗|手术|置换|麻醉|导尿|备皮|禁饮|禁食|吸氧|补液|输液|冰敷|热敷|熏洗|气压治疗|cpm"
    ));
    private List<String> testPatterns = new ArrayList<>(List.of(
            "复查|检查|检验|筛查|病理|培养|彩超|超声|影像|ct|mri|x线|x-ray|血常规|尿常规|凝血|d-二聚体|crp|血沉|因子|指标"
    ));
    private List<String> monitoringPatterns = new ArrayList<>(List.of(
            "监测|观察|评估|随访|记录|动态|跟踪|警惕|关注|复评|查房"
    ));
    private List<String> testBoostKeywords = new ArrayList<>(Arrays.asList("复查", "检查", "检验", "筛查", "病理", "培养"));
    private List<String> monitoringBoostKeywords = new ArrayList<>(Arrays.asList("监测", "观察", "动态", "评估", "随访", "记录"));
    private List<String> treatmentBoostKeywords = new ArrayList<>(Arrays.asList("注射", "口服", "静滴", "治疗", "手术", "抗凝", "抗感染"));

    private List<String> abnormalHintPatterns = new ArrayList<>(List.of(
            "升高|降低|增高|减少|异常|阳性|波动|偏高|偏低|最高\\s*3[8-9]"
    ));
    private List<String> abnormalLabTokens = new ArrayList<>(Arrays.asList(
            "crp", "d-二聚体", "hb", "alt", "ast", "ggt", "inr", "血沉"
    ));

    private List<PrimaryProblemBadgeRule> primaryProblemBadgeRules = new ArrayList<>();
    private List<PrimaryStatusLabelRule> primaryStatusLabelRules = new ArrayList<>();
    private List<EnumLabelRule> certaintyLabelRules = new ArrayList<>();
    private List<EnumLabelRule> statusLabelRules = new ArrayList<>();
    private List<EnumLabelRule> severityLabelRules = new ArrayList<>();
    private List<EnumLabelRule> priorityLabelRules = new ArrayList<>();
    private List<EnumLabelRule> problemTypeLabelRules = new ArrayList<>();

    private int maxBadges = 10;

    public TimelineViewRuleProperties() {
        primaryProblemBadgeRules.add(new PrimaryProblemBadgeRule("贫血", "贫血"));
        primaryProblemBadgeRules.add(new PrimaryProblemBadgeRule("感染", "感染"));
        primaryProblemBadgeRules.add(new PrimaryProblemBadgeRule("血栓|抗凝", "血栓风险"));
        primaryProblemBadgeRules.add(new PrimaryProblemBadgeRule("疼痛", "疼痛"));

        primaryStatusLabelRules.add(new PrimaryStatusLabelRule("active", "活动期"));
        primaryStatusLabelRules.add(new PrimaryStatusLabelRule("acute_exacerbation", "急性加重"));
        primaryStatusLabelRules.add(new PrimaryStatusLabelRule("worsening", "恶化"));
        primaryStatusLabelRules.add(new PrimaryStatusLabelRule("improving", "好转"));

        hideProblemTypePatterns.add(new StatusLabelRule("differential", "鉴别诊断"));

        importantUnconfirmedPatterns.add(new StatusLabelRule("suspected", "疑似"));
        importantUnconfirmedPatterns.add(new StatusLabelRule("possible", "可能"));
        importantUnconfirmedPatterns.add(new StatusLabelRule("workup_needed", "待排/待查"));
        importantUnconfirmedPatterns.add(new StatusLabelRule("risk_only", "风险提示"));

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

        severityLabelRules.add(new EnumLabelRule("mild", "轻度"));
        severityLabelRules.add(new EnumLabelRule("moderate", "中度"));
        severityLabelRules.add(new EnumLabelRule("severe", "重度"));
        severityLabelRules.add(new EnumLabelRule("critical", "危重"));
        severityLabelRules.add(new EnumLabelRule("unclear", "未明"));

        priorityLabelRules.add(new EnumLabelRule("high", "高"));
        priorityLabelRules.add(new EnumLabelRule("medium", "中"));
        priorityLabelRules.add(new EnumLabelRule("low", "低"));

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
    public static class PrimaryStatusLabelRule {
        private String status;
        private String label;

        public PrimaryStatusLabelRule() {
        }

        public PrimaryStatusLabelRule(String status, String label) {
            this.status = status;
            this.label = label;
        }
    }

    @Data
    public static class StatusLabelRule {
        private String status;
        private String label;

        public StatusLabelRule() {
        }

        public StatusLabelRule(String status, String label) {
            this.status = status;
            this.label = label;
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
