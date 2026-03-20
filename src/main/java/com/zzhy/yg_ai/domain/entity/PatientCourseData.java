package com.zzhy.yg_ai.domain.entity;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * 患者全量信息实体（单套结构，包含各子类信息）。
 */
@Data
public class PatientCourseData {

    private PatInfor patInfor;
    private List<PatDiagInfor> patDiagInforList;
    private List<PatBodySurface> patBodySurfaceList;
    private List<PatDoctorAdvice> longDoctorAdviceList;
    private List<PatDoctorAdvice> temporaryDoctorAdviceList;
    private List<PatDoctorAdvice> sgDoctorAdviceList;
    private List<PatIllnessCourse> patIllnessCourseList;
    private List<PatTestSam> patTestSamList;
    private List<PatUseMedicine> patUseMedicineList;
    private List<PatVideoResult> patVideoResultList;
    private List<PatTransfer> patTransferList;
    private List<PatOpsCutInfor> patOpsCutInforList;
    private List<PatTest> patTestList;
    private OtherInfo otherInfo;

    /**
     * 患者基本信息
     */
    @Data
    public static class PatInfor {
        private String reqno;
        private String pathosid;
        private String sex;
        private String age;
        private String disname;
        private String outhodate;
        private LocalDateTime inhosday;
        private String inhosdistrict;
        private Integer hosdays;
    }

    /**
     * 患者诊断信息
     */
    @Data
    public static class PatDiagInfor {
        private String reqno;
        private String phase;
        private String diagName;
        private LocalDateTime diagTime;
    }

    /**
     * 患者体表信息
     */
    @Data
    public static class PatBodySurface {
        private String reqno;
        private LocalDateTime measuredate;
        private String temperature;
        private String stoolCount;
        private String pulse;
        private String breath;
        private String bloodPressure;
    }

    /**
     * 患者医嘱信息
     */
    @Data
    public static class PatDoctorAdvice {
        private String reqno;
        private String docadvice;
        private LocalDateTime begtime;
        private String endtime;
        private String docadvtype;
        private String remarks;
        private String distname;
    }

    /**
     * 患者病程信息
     */
    @Data
    public static class PatIllnessCourse {
        private String reqno;
        private String illnesscontent;
        private LocalDateTime creattime;
        private String itemname;
    }

    /**
     * 患者检验信息
     */
    @Data
    public static class PatTestSam {
        private String reqno;
        private String samreqno;
        private LocalDateTime sendtestdate;
        private String testaim;
        private String dataName;
        private LocalDateTime testdate;
        private List<PatTestResult> resultList;
    }

    /**
     * 患者检验结果信息
     */
    @Data
    public static class PatTestResult {
        private String reqno;
        private String samreqno;
        private String itemname;
        private String engname;
        private String resultdesc;
        private String state;
        private String unit;
        private String refdesc;
        private String allJyFlag;
    }

    /**
     * 患者用药信息
     */
    @Data
    public static class PatUseMedicine {
        private String reqno;
        private String mediName;
        private String medCalss;
        private String mediPath;
        private LocalDateTime beginTime;
        private String zxsj;
        private String endTime;
        private String mediAim;
        private String docadvtype;
        private String mediNum;
        private String medusage;
        private String frequency;
        private String unit;
        private String memo;
        private String distname;
    }

    /**
     * 患者视频结果信息
     */
    @Data
    public static class PatVideoResult {
        private String reqno;
        private String samreqno;
        private String names;
        private String diagnose;
        private String testresult;
        private LocalDateTime reporttime;
    }

    /**
     * 患者转科信息
     */
    @Data
    public static class PatTransfer {
        private String reqno;
        private LocalDateTime indeptdate;
        private String indeptname;
        private String outhodate;
    }

    /**
     * 患者手术信息
     */
    @Data
    public static class PatOpsCutInfor {
        private String reqno;
        private String opsId;
        private String opsName;
        private LocalDateTime begTime;
        private String endTime;
        private String cutType;
        private String hocusMode;
        private List<OpsMedicine> preWardMedicineList;
        private List<OpsMedicine> perioperativeMedicineList;
    }

    /**
     * 患者手术用药信息
     */
    @Data
    public static class OpsMedicine {
        private String reqno;
        private String opsId;
        private String mediName;
        private String dosage;
        private LocalDateTime beginTime;
    }

    /**
     * 患者微生物信息
     */
    @Data
    public static class PatTest {
        private String reqno;
        private String testobject;
        private String samreqno;
        private LocalDateTime sampletime;
        private String dataName;
        private List<MicrobeInfo> microbeList;
    }

    /**
     * 患者检验结果信息
     */
    @Data
    public static class MicrobeInfo {
        private String reqno;
        private String samreqno;
        private LocalDateTime sendtestdate;
        private String samtypename;
        private String samtype;
        private LocalDateTime exedate;
        private String dataCode;
        private String result;
        private String result1;
        private String result2;
        private List<AntiDrugInfo> antiDrugList;
    }

    /**
     * 患者检验抗菌药信息
     */
    @Data
    public static class AntiDrugInfo {
        private String reqno;
        private String samreqno;
        private String microbecode;
        private String mic;
        private String dataName;
        private String sensitivity;
    }

    /**
     * 其他信息
     */
    @Data
    public static class OtherInfo {
        private LocalDateTime queryTime;
        private LocalDateTime sourceLastTime;
        private LocalDateTime dataStartTime;
    }
}
