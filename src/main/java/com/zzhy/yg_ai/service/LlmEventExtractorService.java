package com.zzhy.yg_ai.service;

import com.zzhy.yg_ai.domain.model.EvidenceBlockBuildResult;
import com.zzhy.yg_ai.domain.model.LlmEventExtractorResult;

public interface LlmEventExtractorService {

    LlmEventExtractorResult extractAndSave(EvidenceBlockBuildResult blockBuildResult);
}
