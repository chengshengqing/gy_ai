package com.zzhy.yg_ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzhy.yg_ai.domain.entity.InfectionEventTaskEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface InfectionEventTaskMapper extends BaseMapper<InfectionEventTaskEntity> {

    List<InfectionEventTaskEntity> claimPendingTasks(@Param("taskType") String taskType,
                                                     @Param("now") LocalDateTime now,
                                                     @Param("limit") int limit);
}
