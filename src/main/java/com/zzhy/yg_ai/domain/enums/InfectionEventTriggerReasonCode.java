package com.zzhy.yg_ai.domain.enums;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.StringJoiner;
import org.springframework.util.StringUtils;

public enum InfectionEventTriggerReasonCode {
    ILLNESS_COURSE_CHANGED,
    LAB_RESULT_CHANGED,
    MICROBE_CHANGED,
    IMAGING_CHANGED,
    ANTIBIOTIC_OR_ORDER_CHANGED,
    OPERATION_CHANGED,
    TRANSFER_CHANGED,
    VITAL_SIGN_CHANGED;

    public static String toCsv(Set<InfectionEventTriggerReasonCode> codes) {
        if (codes == null || codes.isEmpty()) {
            return null;
        }
        StringJoiner joiner = new StringJoiner(",");
        for (InfectionEventTriggerReasonCode code : codes) {
            if (code != null) {
                joiner.add(code.name());
            }
        }
        String csv = joiner.toString();
        return csv.isBlank() ? null : csv;
    }

    public static Set<InfectionEventTriggerReasonCode> orderedSet() {
        return new LinkedHashSet<>();
    }

    public static Set<InfectionEventTriggerReasonCode> fromCsv(String csv) {
        Set<InfectionEventTriggerReasonCode> result = orderedSet();
        if (!StringUtils.hasText(csv)) {
            return result;
        }
        for (String token : csv.split(",")) {
            if (!StringUtils.hasText(token)) {
                continue;
            }
            try {
                result.add(valueOf(token.trim()));
            } catch (IllegalArgumentException ignore) {
                // Reject unknown trigger reason codes by omission.
            }
        }
        return result;
    }
}
