package com.zzhy.yg_ai.domain.enums;

import java.util.Arrays;

public enum InfectionEventSubtype {
    FEVER(InfectionEventType.VITAL_SIGN, "fever", "发热"),
    LAB_ABNORMAL(InfectionEventType.LAB_RESULT, "lab_abnormal", "感染相关检验异常"),
    CULTURE_ORDERED(InfectionEventType.MICROBIOLOGY, "culture_ordered", "培养送检"),
    CULTURE_POSITIVE(InfectionEventType.MICROBIOLOGY, "culture_positive", "培养阳性"),
    ANTIBIOTIC_STARTED(InfectionEventType.ORDER, "antibiotic_started", "新开抗菌药"),
    ANTIBIOTIC_UPGRADED(InfectionEventType.ORDER, "antibiotic_upgraded", "抗菌药升级"),
    PROCEDURE_EXPOSURE(InfectionEventType.PROCEDURE, "procedure_exposure", "操作暴露"),
    DEVICE_EXPOSURE(InfectionEventType.DEVICE, "device_exposure", "器械暴露"),
    IMAGING_INFECTION_HINT(InfectionEventType.IMAGING, "imaging_infection_hint", "影像提示感染灶"),
    INFECTION_POSITIVE_STATEMENT(InfectionEventType.ASSESSMENT, "infection_positive_statement", "支持感染"),
    INFECTION_NEGATIVE_STATEMENT(InfectionEventType.ASSESSMENT, "infection_negative_statement", "反对感染"),
    CONTAMINATION_STATEMENT(InfectionEventType.ASSESSMENT, "contamination_statement", "污染判断"),
    CONTAMINATION_POSSIBLE(InfectionEventType.ASSESSMENT, "contamination_possible", "考虑污染"),
    COLONIZATION_STATEMENT(InfectionEventType.ASSESSMENT, "colonization_statement", "定植判断"),
    COLONIZATION_POSSIBLE(InfectionEventType.ASSESSMENT, "colonization_possible", "考虑定植");

    private final InfectionEventType eventType;
    private final String code;
    private final String description;

    InfectionEventSubtype(InfectionEventType eventType, String code, String description) {
        this.eventType = eventType;
        this.code = code;
        this.description = description;
    }

    public InfectionEventType eventType() {
        return eventType;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    public static InfectionEventSubtype fromCode(String code) {
        return Arrays.stream(values())
                .filter(item -> item.code.equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported InfectionEventSubtype code: " + code));
    }
}
