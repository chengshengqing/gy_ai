package com.zzhy.yg_ai.mapper;

import com.zzhy.yg_ai.pipeline.monitor.PipelineMonitorBacklogRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PipelineMonitorQueryMapper {

    List<PipelineMonitorBacklogRow> selectBacklogRows(@Param("now") LocalDateTime now);
}
