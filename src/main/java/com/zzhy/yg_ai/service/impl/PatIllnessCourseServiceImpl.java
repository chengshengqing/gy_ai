package com.zzhy.yg_ai.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzhy.yg_ai.domain.entity.PatIllnessCourse;
import com.zzhy.yg_ai.mapper.PatIllnessCourseMapper;
import com.zzhy.yg_ai.service.IPatIllnessCourseService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 患者病程服务实现类
 */
@Service
public class PatIllnessCourseServiceImpl extends ServiceImpl<PatIllnessCourseMapper, PatIllnessCourse> implements IPatIllnessCourseService {

    @Override
    public List<PatIllnessCourse> getPageByClassifyCourse(Integer pageNum, Integer pageSize) {
        Page<String> page = new Page<>(pageNum, pageSize);
        List<PatIllnessCourse> pageByClassifyCourse = baseMapper.getPageByClassifyCourse(page);
        return pageByClassifyCourse;
    }
}
