package com.zzhy.yg_ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzhy.yg_ai.domain.entity.PatientCourseData;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PatientRawDataMapper extends BaseMapper<PatientRawDataEntity> {

    LocalDateTime selectSourceLastTime();

    LocalDateTime selectStoredLastTimeByReqno(@Param("reqno") String reqno);

    List<String> selectActiveReqnos();

    PatientCourseData.PatInfor selectPatInfor(@Param("reqno") String reqno);

    List<PatientCourseData.PatDiagInfor> selectDiagByReqno(@Param("reqno") String reqno,
                                                           @Param("lastTime") LocalDateTime lastTime);

    List<PatientCourseData.PatBodySurface> selectBodySurfaceByReqno(@Param("reqno") String reqno,
                                                                    @Param("lastTime") LocalDateTime lastTime);

    List<PatientCourseData.PatDoctorAdvice> selectLongDoctorAdviceByReqno(@Param("reqno") String reqno,
                                                                           @Param("lastTime") LocalDateTime lastTime);

    List<PatientCourseData.PatDoctorAdvice> selectTemporaryDoctorAdviceByReqno(@Param("reqno") String reqno,
                                                                                @Param("lastTime") LocalDateTime lastTime);

    List<PatientCourseData.PatDoctorAdvice> selectSgDoctorAdviceByReqno(@Param("reqno") String reqno,
                                                                         @Param("lastTime") LocalDateTime lastTime);

    List<PatientCourseData.PatIllnessCourse> selectIllnessCourseByReqno(@Param("reqno") String reqno,
                                                                         @Param("lastTime") LocalDateTime lastTime);

    List<PatientCourseData.PatTestSam> selectTestSamByReqno(@Param("reqno") String reqno,
                                                             @Param("lastTime") LocalDateTime lastTime);

    List<PatientCourseData.PatTestResult> selectTestResultBySamreqno(@Param("reqno") String reqno,
                                                                      @Param("samreqno") String samreqno,
                                                                      @Param("lastTime") LocalDateTime lastTime);

    List<PatientCourseData.PatUseMedicine> selectUseMedicineByReqno(@Param("reqno") String reqno,
                                                                     @Param("lastTime") LocalDateTime lastTime);

    List<PatientCourseData.PatVideoResult> selectVideoResultByReqno(@Param("reqno") String reqno,
                                                                     @Param("lastTime") LocalDateTime lastTime);

    List<PatientCourseData.PatTransfer> selectTransferByReqno(@Param("reqno") String reqno,
                                                               @Param("lastTime") LocalDateTime lastTime);

    List<PatientCourseData.PatOpsCutInfor> selectOpsByReqno(@Param("reqno") String reqno,
                                                             @Param("lastTime") LocalDateTime lastTime);

    List<PatientCourseData.OpsMedicine> selectPreWardOpsMedicine(@Param("reqno") String reqno,
                                                                  @Param("opsId") String opsId,
                                                                  @Param("lastTime") LocalDateTime lastTime);

    List<PatientCourseData.OpsMedicine> selectPerioperativeOpsMedicine(@Param("reqno") String reqno,
                                                                       @Param("opsId") String opsId,
                                                                       @Param("lastTime") LocalDateTime lastTime);

    List<PatientCourseData.PatTest> selectPatTestByReqno(@Param("reqno") String reqno,
                                                          @Param("lastTime") LocalDateTime lastTime);

    List<PatientCourseData.MicrobeInfo> selectMicrobeBySamreqno(@Param("reqno") String reqno,
                                                                 @Param("samreqno") String samreqno,
                                                                 @Param("lastTime") LocalDateTime lastTime);

    List<PatientCourseData.AntiDrugInfo> selectAntiDrugByMicrobe(@Param("reqno") String reqno,
                                                                  @Param("samreqno") String samreqno,
                                                                  @Param("dataCode") String dataCode,
                                                                  @Param("lastTime") LocalDateTime lastTime);

    List<String> selectReqnosWithUnprocessedStructData(@Param("limit") int limit);

    List<PatientRawDataEntity> selectUnprocessedStructDataByReqno(@Param("reqno") String reqno);

    List<String> selectPendingReqnosForDataHandler(@Param("limit") int limit);

    List<PatientRawDataEntity> selectPendingRowsByReqnoForDataHandler(@Param("reqno") String reqno);
}
