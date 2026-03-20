package com.zzhy.yg_ai.ai.tools;

import com.zzhy.yg_ai.service.PatientService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class LoadDataTool {

    private final PatientService patientService;

    public LoadDataTool(PatientService patientService) {
        this.patientService = patientService;
    }

    @Tool(name = "load_patient_raw_data",
            description = "Load raw patient data by reqno from data sources.")
    public String loadPatientRawData(@ToolParam(description = "Patient reqno") String reqno) {
        return patientService.collectAndSaveRawData(reqno);
    }
}
