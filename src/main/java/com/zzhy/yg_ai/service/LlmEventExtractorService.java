package com.zzhy.yg_ai.service;

import com.zzhy.yg_ai.domain.model.EvidenceBlock;
import com.zzhy.yg_ai.domain.model.EvidenceBlockBuildResult;
import com.zzhy.yg_ai.domain.model.LlmEventExtractorResult;
import java.util.List;

public interface LlmEventExtractorService {

    LlmEventExtractorResult extractAndSave(EvidenceBlockBuildResult blockBuildResult, List<EvidenceBlock> primaryBlocks);
}
