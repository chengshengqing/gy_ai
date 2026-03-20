package com.zzhy.yg_ai.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PatientContext {

    private String reqno;
    private String contextJson;
    private String eventJson;
    private String source;
    private LocalDateTime createdAt;

}
