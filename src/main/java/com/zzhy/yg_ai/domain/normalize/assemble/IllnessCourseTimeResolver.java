package com.zzhy.yg_ai.domain.normalize.assemble;

import com.zzhy.yg_ai.common.DateTimeUtils;
import com.zzhy.yg_ai.domain.entity.PatientCourseData;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

@Component
public class IllnessCourseTimeResolver {

    private static final DateTimeFormatter ILLNESS_TIME_FORMATTER = DateTimeUtils.DATE_TIME_FORMATTER;

    public String resolve(PatientCourseData.PatIllnessCourse illnessCourse, PatientRawDataEntity rawData) {
        LocalDateTime dt = illnessCourse.getChangetime();
        if (dt == null) {
            dt = illnessCourse.getCreattime();
        }
        if (dt == null && rawData.getDataDate() != null) {
            dt = rawData.getDataDate().atTime(LocalTime.MIN);
        }
        return dt == null ? "" : DateTimeUtils.truncateToMillis(dt).format(ILLNESS_TIME_FORMATTER);
    }
}
