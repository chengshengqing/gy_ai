package com.zzhy.yg_ai.domain.model;

import java.util.List;
import lombok.Data;

/**
 * 统一事件抽取器输出包装结构。
 */
@Data
public class LlmExtractedEventBatch {

    private List<LlmExtractedEvent> events;
}
