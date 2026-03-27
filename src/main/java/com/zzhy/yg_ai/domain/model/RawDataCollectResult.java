package com.zzhy.yg_ai.domain.model;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class RawDataCollectResult {

    private String reqno;
    private String status;
    private String message;
    private Integer savedDays;
    private LocalDateTime storedLastTime;
    private LocalDateTime sourceLastTime;
    private String changeTypes;

    public boolean isSuccessLike() {
        return "success".equalsIgnoreCase(status) || "no_data".equalsIgnoreCase(status);
    }
}
