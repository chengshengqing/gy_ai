package com.zzhy.yg_ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.zzhy.yg_ai.domain.dto.PatillnessCourseInfo;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 患者病程信息 Mapper 接口
 */
@Mapper
public interface PatillnessCourseInfoMapper extends BaseMapper<PatillnessCourseInfo> {

    /**
     * 分页查询患者病程信息列表
     */
    List<PatillnessCourseInfo> selectList(IPage page);
}
