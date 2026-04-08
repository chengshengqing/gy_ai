package com.zzhy.yg_ai.domain.schema;

import com.zzhy.yg_ai.domain.enums.EvidenceBlockType;
import com.zzhy.yg_ai.domain.enums.InfectionAbnormalFlag;
import com.zzhy.yg_ai.domain.enums.InfectionBodySite;
import com.zzhy.yg_ai.domain.enums.InfectionClinicalMeaning;
import com.zzhy.yg_ai.domain.enums.InfectionEvidenceRole;
import com.zzhy.yg_ai.domain.enums.InfectionEvidenceTier;
import com.zzhy.yg_ai.domain.enums.InfectionEventCategory;
import com.zzhy.yg_ai.domain.enums.InfectionEventStatus;
import com.zzhy.yg_ai.domain.enums.InfectionEventSubtype;
import com.zzhy.yg_ai.domain.enums.InfectionEventType;
import com.zzhy.yg_ai.domain.enums.InfectionSourceType;
import com.zzhy.yg_ai.domain.enums.InfectionSourceSection;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class InfectionEventSchema {

    private static final Map<String, Set<String>> STRUCTURED_SECTION_EVENT_TYPES = new LinkedHashMap<>();

    static {
        STRUCTURED_SECTION_EVENT_TYPES.put(InfectionSourceSection.DIAGNOSIS.code(), Set.of(InfectionEventType.DIAGNOSIS.code()));
        STRUCTURED_SECTION_EVENT_TYPES.put(InfectionSourceSection.VITAL_SIGNS.code(), Set.of(InfectionEventType.VITAL_SIGN.code()));
        STRUCTURED_SECTION_EVENT_TYPES.put(InfectionSourceSection.LAB_RESULTS.code(), Set.of(
                InfectionEventType.LAB_RESULT.code(),
                InfectionEventType.LAB_PANEL.code(),
                InfectionEventType.MICROBIOLOGY.code()
        ));
        STRUCTURED_SECTION_EVENT_TYPES.put(InfectionSourceSection.IMAGING.code(), Set.of(InfectionEventType.IMAGING.code()));
        STRUCTURED_SECTION_EVENT_TYPES.put(InfectionSourceSection.DOCTOR_ORDERS.code(), Set.of(
                InfectionEventType.ORDER.code(),
                InfectionEventType.DEVICE.code(),
                InfectionEventType.PROCEDURE.code()
        ));
        STRUCTURED_SECTION_EVENT_TYPES.put(InfectionSourceSection.USE_MEDICINE.code(), Set.of(InfectionEventType.ORDER.code()));
        STRUCTURED_SECTION_EVENT_TYPES.put(InfectionSourceSection.TRANSFER.code(), Set.of(
                InfectionEventType.PROBLEM.code(),
                InfectionEventType.ASSESSMENT.code()
        ));
        STRUCTURED_SECTION_EVENT_TYPES.put(InfectionSourceSection.OPERATION.code(), Set.of(
                InfectionEventType.PROCEDURE.code(),
                InfectionEventType.ORDER.code()
        ));
    }

    private InfectionEventSchema() {
    }

    public static List<String> eventTypeCodes() {
        return Arrays.stream(InfectionEventType.values()).map(InfectionEventType::code).toList();
    }

    public static List<String> eventSubtypeCodes() {
        return Arrays.stream(InfectionEventSubtype.values()).map(InfectionEventSubtype::code).toList();
    }

    public static List<String> clinicalMeaningCodes() {
        return Arrays.stream(InfectionClinicalMeaning.values()).map(InfectionClinicalMeaning::code).toList();
    }

    public static List<String> bodySiteCodes() {
        return Arrays.stream(InfectionBodySite.values()).map(InfectionBodySite::code).toList();
    }

    public static List<String> abnormalFlagCodes() {
        return Arrays.stream(InfectionAbnormalFlag.values()).map(InfectionAbnormalFlag::code).toList();
    }

    public static List<String> sourceSectionCodes() {
        return Arrays.stream(InfectionSourceSection.values()).map(InfectionSourceSection::code).toList();
    }

    public static List<String> refinementSourceSectionCodes() {
        return List.of(
                InfectionSourceSection.DOCTOR_ORDERS.code(),
                InfectionSourceSection.USE_MEDICINE.code(),
                InfectionSourceSection.OPERATION.code()
        );
    }

    public static List<String> evidenceTierCodes() {
        return Arrays.stream(InfectionEvidenceTier.values()).map(InfectionEvidenceTier::code).toList();
    }

    public static List<String> evidenceRoleCodes() {
        return Arrays.stream(InfectionEvidenceRole.values()).map(InfectionEvidenceRole::code).toList();
    }

    public static List<String> sourceTypeCodes() {
        return Arrays.stream(InfectionSourceType.values()).map(InfectionSourceType::code).toList();
    }

    public static List<String> eventStatusCodes() {
        return Arrays.stream(InfectionEventStatus.values()).map(InfectionEventStatus::code).toList();
    }

    public static List<String> eventCategoryCodes() {
        return Arrays.stream(InfectionEventCategory.values()).map(InfectionEventCategory::code).toList();
    }

    public static Set<String> allowedEventTypesForSection(String sourceSection) {
        return STRUCTURED_SECTION_EVENT_TYPES.getOrDefault(sourceSection, Set.of());
    }

    public static String joinEventTypes() {
        return String.join("|", eventTypeCodes());
    }

    public static String joinEventSubtypes() {
        return String.join("|", eventSubtypeCodes());
    }

    public static String joinClinicalMeanings() {
        return String.join("|", clinicalMeaningCodes());
    }

    public static String joinBodySites() {
        return String.join("|", bodySiteCodes());
    }

    public static String joinAbnormalFlags() {
        return String.join("|", abnormalFlagCodes());
    }

    public static String joinSourceSections(boolean includeNull) {
        String joined = String.join("|", sourceSectionCodes());
        return includeNull ? joined + "|null" : joined;
    }

    public static String joinRefinementSourceSections() {
        return String.join("|", refinementSourceSectionCodes());
    }

    public static String joinEvidenceTiers() {
        return String.join("|", evidenceTierCodes());
    }

    public static String joinEvidenceRoles() {
        return String.join("|", evidenceRoleCodes());
    }

    public static String resolveEventCategory(EvidenceBlockType blockType) {
        if (blockType == null) {
            return null;
        }
        return switch (blockType) {
            case STRUCTURED_FACT -> InfectionEventCategory.FACT.code();
            case CLINICAL_TEXT -> InfectionEventCategory.TEXT.code();
            case MID_SEMANTIC -> InfectionEventCategory.SEMANTIC.code();
            case TIMELINE_CONTEXT -> InfectionEventCategory.CONTEXT.code();
        };
    }
}
