package com.zzhy.yg_ai.domain.dto.demo;

import java.util.List;

public record InfectionPreReviewDemoSnapshotResult(
        int requested,
        int success,
        int failed,
        List<Item> items
) {
    public record Item(
            String reqno,
            boolean success,
            String message
    ) {
    }
}
