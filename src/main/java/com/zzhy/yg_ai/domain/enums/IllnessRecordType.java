package com.zzhy.yg_ai.domain.enums;

import org.springframework.util.StringUtils;

public enum IllnessRecordType {
    FIRST_COURSE("首次病程记录"),
    ADMISSION("入院记录"),
    CONSULTATION("会诊记录"),
    SURGERY("手术记录"),
    DAILY("日常病程记录");

    private final String label;

    IllnessRecordType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean matches(String text) {
        return StringUtils.hasText(text) && text.contains(label);
    }

    public static IllnessRecordType resolve(String itemName) {
        if (FIRST_COURSE.matches(itemName)) {
            return FIRST_COURSE;
        }
        if (ADMISSION.matches(itemName)) {
            return ADMISSION;
        }
        if (CONSULTATION.matches(itemName)) {
            return CONSULTATION;
        }
        if (SURGERY.matches(itemName)) {
            return SURGERY;
        }
        return DAILY;
    }
}
