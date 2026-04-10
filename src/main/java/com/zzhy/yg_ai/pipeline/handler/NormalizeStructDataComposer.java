package com.zzhy.yg_ai.pipeline.handler;

import com.zzhy.yg_ai.ai.gateway.AiGateway;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.normalize.assemble.DailyFusionPlan;
import com.zzhy.yg_ai.domain.normalize.assemble.NormalizeContext;
import com.zzhy.yg_ai.domain.normalize.assemble.NormalizeInputAssembler;
import com.zzhy.yg_ai.domain.normalize.assemble.NormalizeNoteStructureResult;
import com.zzhy.yg_ai.domain.normalize.assemble.NotePromptTask;
import com.zzhy.yg_ai.domain.normalize.facts.DailyIllnessExtractionResult;
import com.zzhy.yg_ai.domain.normalize.validation.NormalizeResultAssembler;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class NormalizeStructDataComposer {

    private static final String EMPTY_STRUCT_DATA_JSON = "{}";

    private final AiGateway aiGateway;
    private final NormalizeInputAssembler normalizeInputAssembler;
    private final NormalizeResultAssembler normalizeResultAssembler;

    public DailyIllnessExtractionResult compose(PatientRawDataEntity rawData) {
        NormalizeContext context = normalizeInputAssembler.buildContext(rawData);
        if (context == null || !StringUtils.hasText(context.rawInputJson())) {
            return new DailyIllnessExtractionResult(EMPTY_STRUCT_DATA_JSON, null);
        }

        List<NotePromptTask> noteTasks = normalizeInputAssembler.buildNoteTasks(context);
        NormalizeNoteStructureResult noteResult = normalizeResultAssembler.assembleNotes(aiGateway, noteTasks);
        Map<String, Object> dayContext = normalizeInputAssembler.buildDayContext(context, noteResult);
        DailyFusionPlan fusionPlan = normalizeInputAssembler.buildDailyFusionPlan(context, dayContext);
        return normalizeResultAssembler.assembleFinalResult(
                aiGateway,
                rawData,
                dayContext,
                noteResult,
                fusionPlan
        );
    }
}
