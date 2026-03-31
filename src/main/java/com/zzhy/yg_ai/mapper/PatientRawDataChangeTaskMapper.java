package com.zzhy.yg_ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzhy.yg_ai.domain.entity.PatientRawDataChangeTaskEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PatientRawDataChangeTaskMapper extends BaseMapper<PatientRawDataChangeTaskEntity> {

    int insertBatchWithoutId(@Param("tasks") List<PatientRawDataChangeTaskEntity> tasks);

    List<String> selectPendingReqnos(@Param("statuses") List<String> statuses,
                                     @Param("now") LocalDateTime now,
                                     @Param("limit") int limit);
}
