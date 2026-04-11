package com.zzhy.yg_ai.service.normalize;

import com.zzhy.yg_ai.ai.gateway.AiGateway;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.normalize.assemble.DailyFusionPlan;
import com.zzhy.yg_ai.domain.normalize.assemble.NormalizeContext;
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
public class NormalizeStructDataService {

    private static final String EMPTY_STRUCT_DATA_JSON = "{}";

    private final AiGateway aiGateway;
    private final NormalizeContextBuilder normalizeContextBuilder;
    private final NormalizeResultAssembler normalizeResultAssembler;

    public DailyIllnessExtractionResult compose(PatientRawDataEntity rawData) {
        NormalizeContext context = normalizeContextBuilder.buildContext(rawData);
        if (context == null || !context.hasRawInput()) {
            return new DailyIllnessExtractionResult(EMPTY_STRUCT_DATA_JSON, null);
        }

        List<NotePromptTask> noteTasks = normalizeContextBuilder.buildNoteTasks(context);
        NormalizeNoteStructureResult noteResult = normalizeResultAssembler.assembleNotes(aiGateway, noteTasks);
        Map<String, Object> dayContext = normalizeContextBuilder.buildDayContext(context, noteResult);
        DailyFusionPlan fusionPlan = normalizeContextBuilder.buildDailyFusionPlan(context, dayContext);
        return normalizeResultAssembler.assembleFinalResult(
                aiGateway,
                context.dataDate(),
                dayContext,
                noteResult,
                fusionPlan
        );
    }
}
