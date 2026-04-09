package com.zzhy.yg_ai.domain.format;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzhy.yg_ai.ai.agent.AgentUtils;
import com.zzhy.yg_ai.common.FilterTextUtils;
import com.zzhy.yg_ai.domain.entity.PatientCourseData;
import com.zzhy.yg_ai.domain.entity.PatientRawDataEntity;
import com.zzhy.yg_ai.domain.enums.IllnessRecordType;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class FilteredRawDataBuilder {

    private final ObjectMapper objectMapper;
    private final FilterTextUtils filterTextUtils;

    public PatientRawDataEntity build(String firstIllnessCourse, PatientRawDataEntity rawData) {
        if (rawData == null) {
            return null;
        }

        String dataJson = StringUtils.hasText(rawData.getDataJson()) ? rawData.getDataJson() : "{}";
        JsonNode root = AgentUtils.parseToNode(dataJson);
        List<PatientCourseData.PatIllnessCourse> illnessCourseList = readIllnessCourseList(root);
        String normalizedFirstIllnessCourse = StringUtils.hasText(firstIllnessCourse) ? firstIllnessCourse : "";
        for (PatientCourseData.PatIllnessCourse illnessCourse : illnessCourseList) {
            String itemname = illnessCourse.getItemname();
            String illnesscontent = illnessCourse.getIllnesscontent();
            if (IllnessRecordType.FIRST_COURSE.matches(itemname)) {
                illnessCourse.setIllnesscontent(normalizedFirstIllnessCourse);
                continue;
            }
            illnessCourse.setIllnesscontent(
                    filterTextUtils.filterContent(normalizedFirstIllnessCourse, illnesscontent)
            );
        }

        if (root.isObject()) {
            ((ObjectNode) root).set("pat_illnessCourse", objectMapper.valueToTree(illnessCourseList));
            rawData.setFilterDataJson(AgentUtils.toJson(root));
        } else {
            rawData.setFilterDataJson(AgentUtils.toJson(illnessCourseList));
        }
        return rawData;
    }

    private List<PatientCourseData.PatIllnessCourse> readIllnessCourseList(JsonNode root) {
        List<PatientCourseData.PatIllnessCourse> illnessCourseList = objectMapper.convertValue(
                root.path("pat_illnessCourse"),
                new TypeReference<List<PatientCourseData.PatIllnessCourse>>() {
                }
        );
        return illnessCourseList == null ? new ArrayList<>() : illnessCourseList;
    }
}
