package com.zzhy.yg_ai.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zzhy.yg_ai.domain.base.BaseResult;
import com.zzhy.yg_ai.domain.dto.MedicalRecordStructuredDTO;
import com.zzhy.yg_ai.domain.entity.MedicalRecordStructured;
import com.zzhy.yg_ai.service.IMedicalRecordStructuredService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 结构化病历控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/medical-record-structured")
@RequiredArgsConstructor
public class MedicalRecordStructuredController {

    private final IMedicalRecordStructuredService medicalRecordStructuredService;

    /**
     * 根据 ID 查询结构化病历
     */
    @GetMapping("/{id}")
    public BaseResult<MedicalRecordStructured> getById(@PathVariable Long id) {
        MedicalRecordStructured record = medicalRecordStructuredService.getById(id);
        if (record == null) {
            return new BaseResult<>(404, "未找到该结构化病历");
        }
        return new BaseResult<>(record);
    }

    /**
     * 查询所有结构化病历
     */
    @GetMapping("/list")
    public BaseResult<List<MedicalRecordStructured>> list() {
        List<MedicalRecordStructured> list = medicalRecordStructuredService.list();
        return new BaseResult<>(list);
    }

    /**
     * 分页查询结构化病历
     */
    @GetMapping("/page")
    public BaseResult<Page<MedicalRecordStructured>> page(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<MedicalRecordStructured> page = new Page<>(pageNum, pageSize);
        Page<MedicalRecordStructured> result = medicalRecordStructuredService.page(page);
        return new BaseResult<>(result);
    }

    /**
     * 条件查询结构化病历
     */
    @PostMapping("/query")
    public BaseResult<List<MedicalRecordStructured>> query(@RequestBody MedicalRecordStructuredDTO dto) {
        QueryWrapper<MedicalRecordStructured> queryWrapper = new QueryWrapper<>();

        if (dto.getRecordId() != null) {
            queryWrapper.eq("record_id", dto.getRecordId());
        }
        if (dto.getReqno() != null && !dto.getReqno().isEmpty()) {
            queryWrapper.like("reqno", dto.getReqno());
        }
        if (dto.getPathosid() != null && !dto.getPathosid().isEmpty()) {
            queryWrapper.eq("pathosid", dto.getPathosid());
        }
        if (dto.getCourseTime() != null && !dto.getCourseTime().isEmpty()) {
            queryWrapper.like("course_time", dto.getCourseTime());
        }

        queryWrapper.orderByDesc("created_at");

        List<MedicalRecordStructured> list = medicalRecordStructuredService.list(queryWrapper);
        return new BaseResult<>(list);
    }

    /**
     * 新增结构化病历
     */
    @PostMapping
    public BaseResult<Boolean> add(@RequestBody MedicalRecordStructured record) {
        record.init();
        boolean success = medicalRecordStructuredService.save(record);
        return new BaseResult<>(success ? 200 : 500, success ? "添加成功" : "添加失败", success);
    }

    /**
     * 更新结构化病历
     */
    @PutMapping
    public BaseResult<Boolean> update(@RequestBody MedicalRecordStructured record) {
        boolean success = medicalRecordStructuredService.updateById(record);
        return new BaseResult<>(success ? 200 : 500, success ? "更新成功" : "更新失败", success);
    }

    /**
     * 删除结构化病历
     */
    @DeleteMapping("/{id}")
    public BaseResult<Boolean> delete(@PathVariable Long id) {
        boolean success = medicalRecordStructuredService.removeById(id);
        return new BaseResult<>(success ? 200 : 500, success ? "删除成功" : "删除失败", success);
    }

    /**
     * 批量删除结构化病历
     */
    @DeleteMapping("/batch")
    public BaseResult<Boolean> deleteBatch(@RequestBody List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new BaseResult<>(400, "ID 列表不能为空");
        }
        boolean success = medicalRecordStructuredService.removeByIds(ids);
        return new BaseResult<>(success ? 200 : 500, success ? "批量删除成功" : "批量删除失败", success);
    }
}
