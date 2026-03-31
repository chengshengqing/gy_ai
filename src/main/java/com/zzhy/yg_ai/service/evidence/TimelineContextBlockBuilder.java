package com.zzhy.yg_ai.service.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.entity.PatientSummaryEntity;
import com.zzhy.yg_ai.domain.enums.EvidenceBlockType;
import com.zzhy.yg_ai.domain.enums.InfectionSourceType;
import com.zzhy.yg_ai.domain.model.EvidenceBlock;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TimelineContextBlockBuilder extends AbstractEvidenceBlockBuilder {

    public TimelineContextBlockBuilder(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public List<EvidenceBlock> build(PatientRawDataEntity rawData, PatientSummaryEntity latestSummary) {
        ObjectNode root = parseObject(latestSummary == null ? null : latestSummary.getSummaryJson(), "summary_json",
                rawData == null ? null : rawData.getId());
        if (!hasMeaningfulContent(root)) {
            return List.of();
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("section", "summary_json");
        payload.put("source", "summary_json");
        payload.set("data", root.deepCopy());
        return List.of(createBlock(rawData,
                EvidenceBlockType.TIMELINE_CONTEXT,
                InfectionSourceType.SUMMARY,
                "summary_json",
                "timeline_context",
                payload,
                true));
    }
}
