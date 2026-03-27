package com.zzhy.yg_ai.domain.enums;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.util.StringUtils;

public enum PatientCourseDataType {
    FULL_PATIENT,
    DIAGNOSIS,
    BODY_SURFACE,
    DOCTOR_ADVICE,
    ILLNESS_COURSE,
    LAB_TEST,
    USE_MEDICINE,
    VIDEO_RESULT,
    TRANSFER,
    OPERATION,
    MICROBE;

    public static EnumSet<PatientCourseDataType> fullSnapshot() {
        return EnumSet.of(FULL_PATIENT);
    }

    public static EnumSet<PatientCourseDataType> parseCsv(String csv) {
        if (!StringUtils.hasText(csv)) {
            return EnumSet.noneOf(PatientCourseDataType.class);
        }
        EnumSet<PatientCourseDataType> result = EnumSet.noneOf(PatientCourseDataType.class);
        for (String token : csv.split(",")) {
            if (!StringUtils.hasText(token)) {
                continue;
            }
            try {
                result.add(PatientCourseDataType.valueOf(token.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return result;
    }

    public static String toCsv(Set<PatientCourseDataType> types) {
        if (types == null || types.isEmpty()) {
            return null;
        }
        return types.stream()
                .map(Enum::name)
                .sorted()
                .collect(Collectors.joining(","));
    }

    public static EnumSet<PatientCourseDataType> fromNames(Iterable<String> names) {
        EnumSet<PatientCourseDataType> result = EnumSet.noneOf(PatientCourseDataType.class);
        if (names == null) {
            return result;
        }
        for (String name : names) {
            result.addAll(parseCsv(name));
        }
        return result;
    }

    public boolean matchesAny(PatientCourseDataType... candidates) {
        return Arrays.stream(candidates).anyMatch(candidate -> candidate == this);
    }
}
