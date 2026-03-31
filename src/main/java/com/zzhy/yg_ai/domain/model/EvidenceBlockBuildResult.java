package com.zzhy.yg_ai.domain.model;

import java.util.ArrayList;
import java.util.List;

public record EvidenceBlockBuildResult(
        List<EvidenceBlock> structuredFactBlocks,
        List<EvidenceBlock> clinicalTextBlocks,
        List<EvidenceBlock> midSemanticBlocks,
        List<EvidenceBlock> timelineContextBlocks
) {

    public EvidenceBlockBuildResult {
        structuredFactBlocks = copy(structuredFactBlocks);
        clinicalTextBlocks = copy(clinicalTextBlocks);
        midSemanticBlocks = copy(midSemanticBlocks);
        timelineContextBlocks = copy(timelineContextBlocks);
    }

    public List<EvidenceBlock> primaryBlocks() {
        List<EvidenceBlock> result = new ArrayList<>();
        result.addAll(structuredFactBlocks);
        result.addAll(clinicalTextBlocks);
        result.addAll(midSemanticBlocks);
        return List.copyOf(result);
    }

    public List<EvidenceBlock> allBlocks() {
        List<EvidenceBlock> result = new ArrayList<>(primaryBlocks());
        result.addAll(timelineContextBlocks);
        return List.copyOf(result);
    }

    public boolean isEmpty() {
        return structuredFactBlocks.isEmpty()
                && clinicalTextBlocks.isEmpty()
                && midSemanticBlocks.isEmpty()
                && timelineContextBlocks.isEmpty();
    }

    private static List<EvidenceBlock> copy(List<EvidenceBlock> blocks) {
        return blocks == null ? List.of() : List.copyOf(blocks);
    }
}
