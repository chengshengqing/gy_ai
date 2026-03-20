package com.zzhy.yg_ai.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zzhy.yg_ai.domain.base.BaseResult;
import com.zzhy.yg_ai.domain.dto.MedicalEventDTO;
import com.zzhy.yg_ai.domain.entity.MedicalEvent;
import com.zzhy.yg_ai.service.IMedicalEventService;
import com.zzhy.yg_ai.service.IPatIllnessCourseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 医疗事件控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/medical-event")
@RequiredArgsConstructor
public class MedicalEventController {

    private final IMedicalEventService medicalEventService;

    /**
     * 根据 ID 查询医疗事件
     */
    @GetMapping("/{id}")
    public BaseResult<MedicalEvent> getById(@PathVariable Long id) {
        MedicalEvent event = medicalEventService.getById(id);
        if (event == null) {
            return new BaseResult<>(404, "未找到该医疗事件");
        }
        return new BaseResult<>(event);
    }

    /**
     * 查询所有医疗事件
     */
    @GetMapping("/list")
    public BaseResult<List<MedicalEvent>> list() {
        List<MedicalEvent> list = medicalEventService.list();
        return new BaseResult<>(list);
    }

    /**
     * 分页查询医疗事件
     */
    @GetMapping("/page")
    public BaseResult<Page<MedicalEvent>> page(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<MedicalEvent> page = new Page<>(pageNum, pageSize);
        Page<MedicalEvent> result = medicalEventService.page(page);
        return new BaseResult<>(result);
    }

    /**
     * 条件查询医疗事件
     */
    @PostMapping("/query")
    public BaseResult<List<MedicalEvent>> query(@RequestBody MedicalEventDTO dto) {
        if (!dto.validate()) {
            return new BaseResult<>(400, dto.getValidateMessage());
        }

        QueryWrapper<MedicalEvent> queryWrapper = new QueryWrapper<>();

        if (dto.getMedicalRecordStructuredId() != null) {
            queryWrapper.eq("medical_record_structured_id", dto.getMedicalRecordStructuredId());
        }
        if (dto.getRecordId() != null) {
            queryWrapper.eq("record_id", dto.getRecordId());
        }
        if (dto.getReqno() != null && !dto.getReqno().isEmpty()) {
            queryWrapper.like("reqno", dto.getReqno());
        }
        if (dto.getPathosid() != null && !dto.getPathosid().isEmpty()) {
            queryWrapper.eq("pathosid", dto.getPathosid());
        }
        if (dto.getEventType() != null && !dto.getEventType().isEmpty()) {
            queryWrapper.eq("event_type", dto.getEventType());
        }
        if (dto.getEventName() != null && !dto.getEventName().isEmpty()) {
            queryWrapper.like("event_name", dto.getEventName());
        }

        queryWrapper.orderByDesc("event_time").orderByDesc("created_at");

        List<MedicalEvent> list = medicalEventService.list(queryWrapper);
        return new BaseResult<>(list);
    }

    /**
     * 新增医疗事件
     */
    @PostMapping
    public BaseResult<Boolean> add(@RequestBody MedicalEvent event) {
        event.init();
        boolean success = medicalEventService.save(event);
        return new BaseResult<>(success ? 200 : 500, success ? "添加成功" : "添加失败", success);
    }

    /**
     * 更新医疗事件
     */
    @PutMapping
    public BaseResult<Boolean> update(@RequestBody MedicalEvent event) {
        event.update();
        boolean success = medicalEventService.updateById(event);
        return new BaseResult<>(success ? 200 : 500, success ? "更新成功" : "更新失败", success);
    }

    /**
     * 删除医疗事件
     */
    @DeleteMapping("/{id}")
    public BaseResult<Boolean> delete(@PathVariable Long id) {
        boolean success = medicalEventService.removeById(id);
        return new BaseResult<>(success ? 200 : 500, success ? "删除成功" : "删除失败", success);
    }

    /**
     * 批量删除医疗事件
     */
    @DeleteMapping("/batch")
    public BaseResult<Boolean> deleteBatch(@RequestBody List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new BaseResult<>(400, "ID 列表不能为空");
        }
        boolean success = medicalEventService.removeByIds(ids);
        return new BaseResult<>(success ? 200 : 500, success ? "批量删除成功" : "批量删除失败", success);
    }
}
