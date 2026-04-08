package com.zzhy.yg_ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzhy.yg_ai.domain.entity.InfectionEventPoolEntity;
import com.zzhy.yg_ai.domain.enums.InfectionEventStatus;
import com.zzhy.yg_ai.domain.model.NormalizedInfectionEvent;
import com.zzhy.yg_ai.mapper.InfectionEventPoolMapper;
import com.zzhy.yg_ai.service.InfectionEventPoolService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

@Service
public class InfectionEventPoolServiceImpl
        extends ServiceImpl<InfectionEventPoolMapper, InfectionEventPoolEntity>
        implements InfectionEventPoolService {

    @Override
    public InfectionEventPoolEntity createEvent(InfectionEventPoolEntity entity) {
        validateRequiredFields(entity);
        entity.initForCreate();
        this.save(entity);
        return entity;
    }

    @Override
    public InfectionEventPoolEntity saveOrUpdateByEventKey(InfectionEventPoolEntity entity) {
        validateRequiredFields(entity);
        InfectionEventPoolEntity existing = this.baseMapper.selectOne(new QueryWrapper<InfectionEventPoolEntity>()
                .eq("event_key", entity.getEventKey())
                .orderByDesc("event_time")
                .last("OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY"));
        if (existing == null) {
            return createEvent(entity);
        }

        InfectionEventPoolEntity update = new InfectionEventPoolEntity();
        update.setId(existing.getId());
        copyUpdatableFields(entity, update);
        update.setEventKey(existing.getEventKey());
        update.setStatus(StringUtils.hasText(entity.getStatus()) ? entity.getStatus() : InfectionEventStatus.ACTIVE.code());
        update.touch();
        this.updateById(update);
        update.setCreatedAt(existing.getCreatedAt());
        return update;
    }

    @Override
    public InfectionEventPoolEntity saveNormalizedEvent(NormalizedInfectionEvent event) {
        Assert.notNull(event, "normalized infection event must not be null");
        return saveOrUpdateByEventKey(toEntity(event));
    }

    @Override
    public List<InfectionEventPoolEntity> saveNormalizedEvents(List<NormalizedInfectionEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<InfectionEventPoolEntity> result = new ArrayList<>();
        for (NormalizedInfectionEvent event : events) {
            result.add(saveNormalizedEvent(event));
        }
        return List.copyOf(result);
    }

    @Override
    public List<InfectionEventPoolEntity> listActiveEvents(String reqno) {
        if (!StringUtils.hasText(reqno)) {
            return Collections.emptyList();
        }
        return this.baseMapper.selectList(new QueryWrapper<InfectionEventPoolEntity>()
                .eq("reqno", reqno.trim())
                .eq("status", InfectionEventStatus.ACTIVE.code())
                .eq("is_active", true)
                .orderByAsc("event_time", "id"));
    }

    @Override
    public Long getLatestActiveEventVersion(String reqno) {
        if (!StringUtils.hasText(reqno)) {
            return 0L;
        }
        InfectionEventPoolEntity latest = this.baseMapper.selectOne(new QueryWrapper<InfectionEventPoolEntity>()
                .eq("reqno", reqno.trim())
                .eq("status", InfectionEventStatus.ACTIVE.code())
                .eq("is_active", true)
                .orderByDesc("id")
                .last("OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY"));
        return latest == null || latest.getId() == null ? 0L : latest.getId();
    }

    private void validateRequiredFields(InfectionEventPoolEntity entity) {
        Assert.notNull(entity, "infectionEventPool entity must not be null");
        Assert.hasText(entity.getReqno(), "infectionEventPool.reqno must not be blank");
        Assert.hasText(entity.getSourceType(), "infectionEventPool.sourceType must not be blank");
        Assert.hasText(entity.getEventKey(), "infectionEventPool.eventKey must not be blank");
        Assert.hasText(entity.getEventType(), "infectionEventPool.eventType must not be blank");
    }

    private InfectionEventPoolEntity toEntity(NormalizedInfectionEvent event) {
        InfectionEventPoolEntity entity = new InfectionEventPoolEntity();
        BeanUtils.copyProperties(event, entity);
        return entity;
    }

    private void copyUpdatableFields(InfectionEventPoolEntity source, InfectionEventPoolEntity target) {
        BeanUtils.copyProperties(source, target, "id", "eventKey", "status", "createdAt", "updatedAt");
    }
}
