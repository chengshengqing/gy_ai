package com.zzhy.yg_ai.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.zzhy.yg_ai.domain.entity.PatIllnessCourse;

import java.util.List;

/**
 * 患者病程服务接口
 */
public interface IPatIllnessCourseService extends IService<PatIllnessCourse> {

    /**
     * 分页查询待分类的病程信息
     */
    List<PatIllnessCourse> getPageByClassifyCourse(Integer pageNum, Integer pageSize);
}
