package com.zzhy.yg_ai.service.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.enums.EvidenceBlockType;
import com.zzhy.yg_ai.domain.enums.InfectionSourceType;
import com.zzhy.yg_ai.domain.model.EvidenceBlock;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

@Slf4j
@RequiredArgsConstructor
abstract class AbstractEvidenceBlockBuilder implements EvidenceBlockBuilder {

    protected final ObjectMapper objectMapper;

    protected ObjectNode parseObject(String json, String sourceName, Long rawDataId) {
        if (!StringUtils.hasText(json)) {
            return JsonNodeFactory.instance.objectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node != null && node.isObject()) {
                return (ObjectNode) node;
            }
        } catch (Exception e) {
            log.warn("EvidenceBlock 构建时 JSON 解析失败，sourceName={}, rawDataId={}", sourceName, rawDataId, e);
        }
        return JsonNodeFactory.instance.objectNode();
    }

    protected boolean hasMeaningfulContent(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isTextual()) {
            return StringUtils.hasText(node.asText());
        }
        if (node.isNumber() || node.isBoolean()) {
            return true;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (hasMeaningfulContent(item)) {
                    return true;
                }
            }
            return false;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                if (hasMeaningfulContent(fields.next().getValue())) {
                    return true;
                }
            }
            return false;
        }
        return !node.isEmpty();
    }

    protected String toJson(JsonNode payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? JsonNodeFactory.instance.objectNode() : payload);
        } catch (Exception e) {
            throw new IllegalStateException("EvidenceBlock payload 序列化失败", e);
        }
    }

    protected EvidenceBlock createBlock(PatientRawDataEntity rawData,
                                        EvidenceBlockType blockType,
                                        InfectionSourceType sourceType,
                                        String sourceRef,
                                        String title,
                                        JsonNode payload,
                                        boolean contextOnly) {
        String seed = String.join("|",
                value(rawData == null ? null : rawData.getReqno()),
                String.valueOf(rawData == null ? null : rawData.getId()),
                String.valueOf(rawData == null ? null : rawData.getDataDate()),
                blockType.name(),
                value(sourceRef));
        return new EvidenceBlock(
                UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString(),
                rawData == null ? null : rawData.getReqno(),
                rawData == null ? null : rawData.getId(),
                rawData == null ? null : rawData.getDataDate(),
                blockType,
                sourceType,
                sourceRef,
                title,
                toJson(payload),
                contextOnly
        );
    }

    protected String value(String input) {
        return input == null ? "" : input;
    }
}
