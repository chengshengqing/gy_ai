package com.zzhy.yg_ai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zzhy.yg_ai.domain.dto.PatillnessCourseInfo;

import java.util.List;

/**
 * 患者病程信息服务接口
 */
public interface IPatillnessCourseInfoService extends IService<PatillnessCourseInfo> {

    /**
     * 分页查询患者病程信息列表
     */
    List<PatillnessCourseInfo> selectList(Page page);

}
