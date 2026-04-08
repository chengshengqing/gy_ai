package com.zzhy.yg_ai.domain.enums;

import java.util.Arrays;

/**
 * 病例重算触发优先级。
 * 对应 infection_event_task.trigger_priority。
 */
public enum InfectionTriggerPriority {
    HIGH("high", "高优先级"),
    NORMAL("normal", "普通优先级");

    private final String code;
    private final String description;

    InfectionTriggerPriority(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    public static InfectionTriggerPriority fromCode(String code) {
        return Arrays.stream(values())
                .filter(item -> item.code.equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported InfectionTriggerPriority code: " + code));
    }

    public static InfectionTriggerPriority fromCodeOrDefault(String code, InfectionTriggerPriority defaultValue) {
        try {
            return fromCode(code);
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }

    public boolean isHigherThan(InfectionTriggerPriority other) {
        return this == HIGH && other != HIGH;
    }

    public static InfectionTriggerPriority upgrade(InfectionTriggerPriority existing, InfectionTriggerPriority incoming) {
        if (existing == HIGH || incoming == HIGH) {
            return HIGH;
        }
        return NORMAL;
    }
}
