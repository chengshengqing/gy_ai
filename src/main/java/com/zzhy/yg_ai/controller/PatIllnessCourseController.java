package com.zzhy.yg_ai.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zzhy.yg_ai.domain.base.BaseResult;
import com.zzhy.yg_ai.domain.dto.PatIllnessCourseDTO;
import com.zzhy.yg_ai.domain.entity.PatIllnessCourse;
import com.zzhy.yg_ai.service.IPatIllnessCourseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 患者病程控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/pat-illness-course")
@RequiredArgsConstructor
public class PatIllnessCourseController {

    private final IPatIllnessCourseService patIllnessCourseService;

    /**
     * 根据 ID 查询患者病程
     * @param id 病程 ID
     * @return 病程详情
     */
    @GetMapping("/{id}")
    public BaseResult<PatIllnessCourse> getById(@PathVariable Long id) {
        PatIllnessCourse course = patIllnessCourseService.getById(id);
        if (course == null) {
            return new BaseResult<>(404, "未找到该患者病程");
        }
        return new BaseResult<>(course);
    }

    /**
     * 查询所有患者病程
     * @return 病程列表
     */
    @GetMapping("/list")
    public BaseResult<List<PatIllnessCourse>> list() {
        List<PatIllnessCourse> list = patIllnessCourseService.list();
        return new BaseResult<>(list);
    }

    /**
     * 分页查询患者病程
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @return 分页结果
     */
    @GetMapping("/page")
    public BaseResult<Page<PatIllnessCourse>> page(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<PatIllnessCourse> page = new Page<>(pageNum, pageSize);
        Page<PatIllnessCourse> result = patIllnessCourseService.page(page);
        return new BaseResult<>(result);
    }

    /**
     * 条件查询患者病程
     * @param dto 查询条件
     * @return 查询结果
     */
    @PostMapping("/query")
    public BaseResult<List<PatIllnessCourse>> query(@RequestBody PatIllnessCourseDTO dto) {
        QueryWrapper<PatIllnessCourse> queryWrapper = new QueryWrapper<>();
        
        // 根据请求号模糊查询
        if (dto.getReqno() != null && !dto.getReqno().isEmpty()) {
            queryWrapper.like("reqno", dto.getReqno());
        }
        
        // 根据病原体 ID 查询
        if (dto.getPathosid() != null && !dto.getPathosid().isEmpty()) {
            queryWrapper.eq("pathosid", dto.getPathosid());
        }
        
        // 根据项目名称模糊查询
        if (dto.getItemname() != null && !dto.getItemname().isEmpty()) {
            queryWrapper.like("itemname", dto.getItemname());
        }
        
        // 根据创建人模糊查询
        if (dto.getCreatperson() != null && !dto.getCreatperson().isEmpty()) {
            queryWrapper.like("creatperson", dto.getCreatperson());
        }
        
        // 根据住院汇总模糊查询
        if (dto.getInhossum() != null && !dto.getInhossum().isEmpty()) {
            queryWrapper.like("inhossum", dto.getInhossum());
        }
        
        // 根据文件名模糊查询
        if (dto.getFileName() != null && !dto.getFileName().isEmpty()) {
            queryWrapper.like("file_name", dto.getFileName());
        }

        // 按创建时间倒序
        queryWrapper.orderByDesc("creatTime");

        List<PatIllnessCourse> list = patIllnessCourseService.list(queryWrapper);
        return new BaseResult<>(list);
    }

    /**
     * 新增患者病程
     * @param course 病程信息
     * @return 操作结果
     */
    @PostMapping
    public BaseResult<Boolean> add(@RequestBody PatIllnessCourse course) {
        course.init();
        boolean success = patIllnessCourseService.save(course);
        return new BaseResult<>(success ? 200 : 500, success ? "添加成功" : "添加失败", success);
    }

    /**
     * 更新患者病程
     * @param course 病程信息
     * @return 操作结果
     */
    @PutMapping
    public BaseResult<Boolean> update(@RequestBody PatIllnessCourse course) {
        course.update();
        boolean success = patIllnessCourseService.updateById(course);
        return new BaseResult<>(success ? 200 : 500, success ? "更新成功" : "更新失败", success);
    }

    /**
     * 删除患者病程
     * @param id 病程 ID
     * @return 操作结果
     */
    @DeleteMapping("/{id}")
    public BaseResult<Boolean> delete(@PathVariable Long id) {
        boolean success = patIllnessCourseService.removeById(id);
        return new BaseResult<>(success ? 200 : 500, success ? "删除成功" : "删除失败", success);
    }

    /**
     * 批量删除患者病程
     * @param ids 病程 ID 列表
     * @return 操作结果
     */
    @DeleteMapping("/batch")
    public BaseResult<Boolean> deleteBatch(@RequestBody List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new BaseResult<>(400, "ID 列表不能为空");
        }
        boolean success = patIllnessCourseService.removeByIds(ids);
        return new BaseResult<>(success ? 200 : 500, success ? "批量删除成功" : "批量删除失败", success);
    }
}
