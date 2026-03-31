package com.zzhy.yg_ai.domain.enums;

import java.util.Arrays;

public enum InfectionBodySite {
    URINARY("urinary", "泌尿道"),
    RESPIRATORY("respiratory", "呼吸系统"),
    UPPER_RESPIRATORY("upper_respiratory", "上呼吸道"),
    LOWER_RESPIRATORY("lower_respiratory", "下呼吸道"),
    PLEURAL("pleural", "胸膜腔"),
    CARDIAC_VALVE("cardiac_valve", "心脏瓣膜"),
    MYOCARDIAL_PERICARDIAL("myocardial_pericardial", "心肌或心包"),
    MEDIASTINUM("mediastinum", "纵隔"),
    VASCULAR("vascular", "动静脉"),
    BLOODSTREAM("bloodstream", "血流"),
    BLOOD("blood", "血液"),
    GASTROINTESTINAL("gastrointestinal", "胃肠道"),
    ABDOMINAL("abdominal", "腹盆腔"),
    INTRA_ABDOMINAL("intra_abdominal", "腹盆腔内组织"),
    CENTRAL_NERVOUS_SYSTEM("central_nervous_system", "中枢神经系统"),
    SURGICAL_SITE("surgical_site", "手术部位"),
    SUPERFICIAL_INCISION("superficial_incision", "表浅切口"),
    DEEP_INCISION("deep_incision", "深部切口"),
    ORGAN_SPACE("organ_space", "器官腔隙"),
    SKIN_SOFT_TISSUE("skin_soft_tissue", "皮肤软组织"),
    BURN("burn", "烧伤部位"),
    JOINT("joint", "关节"),
    BONE_JOINT("bone_joint", "骨和关节"),
    GENITAL("genital", "生殖系统"),
    EYE_EAR_ORAL("eye_ear_oral", "眼耳口腔"),
    SYSTEMIC("systemic", "全身性"),
    UNKNOWN("unknown", "未知"),
    OTHER("other", "其他");

    private final String code;
    private final String description;

    InfectionBodySite(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    public static InfectionBodySite fromCode(String code) {
        return Arrays.stream(values())
                .filter(item -> item.code.equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported InfectionBodySite code: " + code));
    }
}
