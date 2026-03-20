package com.zzhy.yg_ai.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zzhy.yg_ai.domain.dto.PatillnessCourseInfo;
import com.zzhy.yg_ai.mapper.PatillnessCourseInfoMapper;
import com.zzhy.yg_ai.service.IPatillnessCourseInfoService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 患者病程信息服务实现类
 */
@Service
public class PatillnessCourseInfoServiceImpl extends ServiceImpl<PatillnessCourseInfoMapper, PatillnessCourseInfo> implements IPatillnessCourseInfoService {

    @Override
    public List<PatillnessCourseInfo> selectList(Page page) {
        return baseMapper.selectList(page);
    }

}
