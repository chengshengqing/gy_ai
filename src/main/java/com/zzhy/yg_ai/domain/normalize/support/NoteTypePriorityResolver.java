package com.zzhy.yg_ai.domain.normalize.support;

import com.zzhy.yg_ai.domain.enums.IllnessRecordType;
import org.springframework.stereotype.Component;

@Component
public class NoteTypePriorityResolver {

    public int resolvePriority(String noteType) {
        IllnessRecordType type = IllnessRecordType.resolve(noteType);
        return switch (type) {
            case FIRST_COURSE, ADMISSION -> 1;
            case SURGERY -> 2;
            case CONSULTATION -> 3;
            case DAILY -> 4;
        };
    }
}
