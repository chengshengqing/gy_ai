package com.zzhy.yg_ai.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzhy.yg_ai.domain.entity.InfectionEventPoolEntity;
import com.zzhy.yg_ai.mapper.InfectionEventPoolMapper;
import com.zzhy.yg_ai.service.InfectionEventPoolService;
import org.springframework.stereotype.Service;

@Service
public class InfectionEventPoolServiceImpl
        extends ServiceImpl<InfectionEventPoolMapper, InfectionEventPoolEntity>
        implements InfectionEventPoolService {
}
