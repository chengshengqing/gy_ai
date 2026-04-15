package com.zzhy.yg_ai.service.event;

import com.zzhy.yg_ai.common.DateTimeUtils;
import com.zzhy.yg_ai.domain.model.EvidenceBlock;
import com.zzhy.yg_ai.domain.model.NormalizedInfectionEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class InfectionEventKeySupport {

    public String buildEventKey(EvidenceBlock block, NormalizedInfectionEvent event, String sourceSection) {
        String eventKeyPrefix = buildBlockEventKeyPrefix(block);
        if (!StringUtils.hasText(eventKeyPrefix)) {
            throw new IllegalStateException("Failed to build event key prefix for block");
        }
        String businessKey = String.join("|",
                defaultIfBlank(sourceSection, ""),
                defaultIfBlank(event == null ? null : event.getEventType(), ""),
                defaultIfBlank(event == null ? null : event.getEventSubtype(), ""),
                event == null || event.getEventTime() == null ? "" : DateTimeUtils.format(event.getEventTime()),
                defaultIfBlank(event == null ? null : event.getSite(), ""),
                defaultIfBlank(event == null ? null : event.getTitle(), ""),
                defaultIfBlank(event == null ? null : event.getContent(), ""));
        return eventKeyPrefix + shortHash(businessKey);
    }

    public String buildBlockEventKeyPrefix(EvidenceBlock block) {
        if (block == null
                || !StringUtils.hasText(block.reqno())
                || block.sourceType() == null
                || !StringUtils.hasText(block.blockKey())) {
            return "";
        }
        return String.join("|",
                defaultIfBlank(block.reqno(), ""),
                block.dataDate() == null ? "" : block.dataDate().toString(),
                block.sourceType() == null ? "" : block.sourceType().code(),
                sourceModule(block.sourceRef()),
                defaultIfBlank(block.blockKey(), "")) + "|";
    }

    private String sourceModule(String sourceRef) {
        String module = defaultIfBlank(sourceRef, "");
        int index = module.indexOf('.');
        if (index > 0) {
            module = module.substring(0, index);
        }
        return module;
    }

    private String shortHash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(defaultIfBlank(raw, "").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, 12);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash event business key", e);
        }
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }
}
