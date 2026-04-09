package com.zzhy.yg_ai.domain.normalize.assemble;

import java.util.List;
import java.util.Map;

public record NormalizeNoteStructureResult(
        List<Map<String, Object>> noteStructuredList,
        List<Map<String, Object>> noteValidationList
) {
}
