package com.zzhy.yg_ai.ai.agent;

import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.format.FilteredRawDataBuilder;
import com.zzhy.yg_ai.domain.format.FormatContextComposer;
import com.zzhy.yg_ai.domain.model.PatientContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FormatAgent {

    private final FormatContextComposer formatContextComposer;
    private final FilteredRawDataBuilder filteredRawDataBuilder;

    public PatientContext format(String rawDataJson, String inhosdateRawJson) {
        return formatContextComposer.compose(rawDataJson, inhosdateRawJson);
    }

    public PatientRawDataEntity filterIllnessCourse(String firstIllnessCourse, PatientRawDataEntity rawData) {
        return filteredRawDataBuilder.build(firstIllnessCourse, rawData);
    }
}
