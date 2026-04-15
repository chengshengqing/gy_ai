package com.zzhy.yg_ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzhy.yg_ai.common.DateTimeUtils;
import com.zzhy.yg_ai.domain.entity.InfectionEventPoolEntity;
import com.zzhy.yg_ai.domain.enums.InfectionEventStatus;
import com.zzhy.yg_ai.domain.model.NormalizedInfectionEvent;
import com.zzhy.yg_ai.mapper.InfectionEventPoolMapper;
import com.zzhy.yg_ai.service.InfectionEventPoolService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

@Service
public class InfectionEventPoolServiceImpl
        extends ServiceImpl<InfectionEventPoolMapper, InfectionEventPoolEntity>
        implements InfectionEventPoolService {

    private static final LocalDate EVENT_VERSION_EPOCH = LocalDate.of(2000, 1, 1);
    private static final long EVENT_VERSION_ID_SUFFIX_MOD = 1_000_000L;

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
        touchAfterExisting(update, existing);
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
    public int deleteByEventKeyPrefix(String eventKeyPrefix) {
        if (!isSafeEventKeyPrefix(eventKeyPrefix)) {
            return 0;
        }
        return this.baseMapper.delete(new QueryWrapper<InfectionEventPoolEntity>()
                .likeRight("event_key", eventKeyPrefix.trim()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<InfectionEventPoolEntity> replaceNormalizedEventsByEventKeyPrefix(String eventKeyPrefix,
                                                                                  List<NormalizedInfectionEvent> events) {
        deleteByEventKeyPrefix(eventKeyPrefix);
        return saveNormalizedEvents(events);
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
                .orderByDesc("updated_at", "id")
                .last("OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY"));
        return buildEventPoolVersion(latest);
    }

    private void validateRequiredFields(InfectionEventPoolEntity entity) {
        Assert.notNull(entity, "infectionEventPool entity must not be null");
        Assert.hasText(entity.getReqno(), "infectionEventPool.reqno must not be blank");
        Assert.hasText(entity.getSourceType(), "infectionEventPool.sourceType must not be blank");
        Assert.hasText(entity.getEventKey(), "infectionEventPool.eventKey must not be blank");
        Assert.hasText(entity.getEventType(), "infectionEventPool.eventType must not be blank");
    }

    private boolean isSafeEventKeyPrefix(String eventKeyPrefix) {
        if (!StringUtils.hasText(eventKeyPrefix)) {
            return false;
        }
        String prefix = eventKeyPrefix.trim();
        return prefix.endsWith("|")
                && prefix.chars().filter(ch -> ch == '|').count() >= 5;
    }

    private InfectionEventPoolEntity toEntity(NormalizedInfectionEvent event) {
        InfectionEventPoolEntity entity = new InfectionEventPoolEntity();
        BeanUtils.copyProperties(event, entity);
        return entity;
    }

    private void copyUpdatableFields(InfectionEventPoolEntity source, InfectionEventPoolEntity target) {
        BeanUtils.copyProperties(source, target, "id", "eventKey", "status", "createdAt", "updatedAt");
    }

    private void touchAfterExisting(InfectionEventPoolEntity update, InfectionEventPoolEntity existing) {
        LocalDateTime updateTime = DateTimeUtils.now();
        if (existing != null && existing.getUpdatedAt() != null && !updateTime.isAfter(existing.getUpdatedAt())) {
            updateTime = existing.getUpdatedAt().plus(1, ChronoUnit.MILLIS);
        }
        update.setUpdatedAt(updateTime);
    }

    private Long buildEventPoolVersion(InfectionEventPoolEntity latest) {
        if (latest == null) {
            return 0L;
        }
        LocalDateTime updatedAt = latest.getUpdatedAt();
        if (updatedAt == null) {
            return latest.getId() == null ? 0L : latest.getId();
        }
        long days = ChronoUnit.DAYS.between(EVENT_VERSION_EPOCH, updatedAt.toLocalDate());
        long millisOfDay = updatedAt.toLocalTime().toSecondOfDay() * 1000L + updatedAt.getNano() / 1_000_000L;
        long timestampVersion = days * 86_400_000L + millisOfDay;
        long idSuffix = latest.getId() == null ? 0L : Math.floorMod(latest.getId(), EVENT_VERSION_ID_SUFFIX_MOD);
        return timestampVersion * EVENT_VERSION_ID_SUFFIX_MOD + idSuffix;
    }
}
