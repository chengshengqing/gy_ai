package com.zzhy.yg_ai.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 患者病程实体
 */
@Data
@TableName("pat_illness_course")
public class PatIllnessCourse implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId
    private Integer id;

    /**
     * 请求号
     */
    private String reqno;

    /**
     * 病原体 ID
     */
    private String pathosid;

    /**
     * 病程内容（表列名 IllnessContent）
     */
    private String illnessContent;

    /**
     * 创建时间
     */
    private LocalDateTime creatTime;

    /**
     * 项目名称
     */
    private String itemname;

    /**
     * 创建人
     */
    private String creatPerson;

    /**
     * 住院汇总
     */
    private String inhossum;

    /**
     * 文件名（表列名 file_name）
     */
    private String fileName;

    /**
     * 变更时间
     */
    private LocalDateTime changeTime;

    /**
     * 创建日期（表列名 creat_Date）
     */
    private LocalDateTime creatDate;

    public void init() {
        LocalDateTime now = LocalDateTime.now();
        this.creatTime = now;
        this.creatDate = now;
    }

    public void update() {
        this.changeTime = LocalDateTime.now();
    }
}
