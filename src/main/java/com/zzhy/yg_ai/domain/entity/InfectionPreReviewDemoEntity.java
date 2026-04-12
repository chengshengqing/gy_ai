package com.zzhy.yg_ai.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import lombok.Data;

@Data
@TableName("infection_pre_review_demo")
public class InfectionPreReviewDemoEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "reqno", type = IdType.INPUT)
    private String reqno;
    private String timelineHtml;
    private String aiPreReviewJson;
}
