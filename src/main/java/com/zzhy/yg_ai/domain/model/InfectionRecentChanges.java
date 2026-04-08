package com.zzhy.yg_ai.domain.model;

import java.util.List;
import lombok.Builder;

@Builder
public record InfectionRecentChanges(
        List<String> changes
) {
    public InfectionRecentChanges {
        changes = changes == null ? List.of() : List.copyOf(changes);
    }
}
