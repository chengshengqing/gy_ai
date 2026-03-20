package com.zzhy.yg_ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zzhy.yg_ai.domain.entity.PatIllnessCourse;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 患者病程 Mapper 接口
 */
@Mapper
public interface PatIllnessCourseMapper extends BaseMapper<PatIllnessCourse> {

    /**
     * 分页查询待分类的病程内容（仅 IllnessContent 字段）
     */
    List<PatIllnessCourse> getPageByClassifyCourse(IPage page);
}
