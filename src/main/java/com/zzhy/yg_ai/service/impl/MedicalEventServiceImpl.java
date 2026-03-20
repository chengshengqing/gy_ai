package com.zzhy.yg_ai.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzhy.yg_ai.domain.entity.MedicalEvent;
import com.zzhy.yg_ai.mapper.MedicalEventMapper;
import com.zzhy.yg_ai.service.IMedicalEventService;
import org.springframework.stereotype.Service;

/**
 * 医疗事件服务实现类
 */
@Service
public class MedicalEventServiceImpl extends ServiceImpl<MedicalEventMapper, MedicalEvent> implements IMedicalEventService {
}
