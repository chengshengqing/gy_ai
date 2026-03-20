package com.zzhy.yg_ai.ai.orchestrator;

import com.zzhy.yg_ai.ai.agent.MedicalStructAgent;
import com.zzhy.yg_ai.ai.rule.InfectionRuleEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AiPipelineOrchestrator {
    @Autowired
    private MedicalStructAgent structAgent;

    @Autowired
    private InfectionRuleEngine ruleEngine;

    public void process(Long patientId, String text) {

        String structured = structAgent.run();

        ruleEngine.analyze(patientId, structured);
    }
}
