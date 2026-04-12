package com.zzhy.yg_ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzhy.yg_ai.domain.dto.demo.InfectionPreReviewSourceRow;
import com.zzhy.yg_ai.domain.entity.InfectionPreReviewDemoEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface InfectionPreReviewDemoMapper extends BaseMapper<InfectionPreReviewDemoEntity> {

    InfectionPreReviewSourceRow selectLatestPreReviewByReqno(@Param("reqno") String reqno);

    int upsertDemoSnapshot(InfectionPreReviewDemoEntity entity);
}
