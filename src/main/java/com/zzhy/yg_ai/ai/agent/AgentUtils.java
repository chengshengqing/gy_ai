package com.zzhy.yg_ai.ai.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzhy.yg_ai.common.DateTimeUtils;
import com.zzhy.yg_ai.domain.enums.IllnessRecordType;

import java.util.*;
import java.util.function.Function;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
public class AgentUtils {

    private static final int MAX_SECTION_LENGTH = 10000;
    private static final int CHUNK_TARGET_LENGTH = 5500;

    private static final ObjectMapper objectMapper = DateTimeUtils.createObjectMapper();


    /**
     * 分割输入JSON为病程信息和其他信息
     * @param inputJson 输入JSON字符串
     * @return 包含illnessJson和otherJson的Map
     */
    public static Map<String, String> splitInput(String inputJson) {
        String input = StringUtils.hasText(inputJson) ? inputJson : "{}";
        Map<String, String> result = new LinkedHashMap<>();

        try {
            JsonNode root = objectMapper.readTree(input);

            JsonNode illnessCourseNode = root.path("pat_illnessCourse");
            ObjectNode otherInfoNode = objectMapper.createObjectNode();
            Iterator<String> fieldNames = root.fieldNames();
            while (fieldNames.hasNext()) {
                String key = fieldNames.next();
                if (!"pat_illnessCourse".equals(key)) {
                    JsonNode clinicalNotes = root.get("clinical_notes");
                    boolean hasFirstCourse = false;
                    if (clinicalNotes != null && clinicalNotes.isArray()) {
                        for (JsonNode note : clinicalNotes) {
                            if (IllnessRecordType.FIRST_COURSE.matches(note.asText())) {
                                hasFirstCourse = true;
                                break;
                            }
                        }
                    }
                    if (!hasFirstCourse && "patient_info".equals(key)) {
                        continue;
                    }
                    otherInfoNode.set(key, root.get(key));
                }
            }

            result.put("illnessJson", toJson(illnessCourseNode));
            result.put("otherJson", toJson(otherInfoNode));
        } catch (Exception e) {
            log.error("分割输入JSON失败", e);
            result.put("illnessJson", "{}");
            result.put("otherJson", "{}");
        }

        return result;
    }


    /**
     * 处理并合并结果
     * @param illnessPart 病程部分处理结果
     * @param otherPart 其他部分处理结果
     * @return 合并后的结果
     */
    public static Map<String, Object> prepareMergeInput(String illnessPart, String otherPart) {
        Map<String, Object> mergeInput = new LinkedHashMap<>();
        mergeInput.put("illness_course_part", parseToNode(illnessPart));
        mergeInput.put("other_info_part", parseToNode(otherPart));
        return mergeInput;
    }

    public static String formatSectionWithSplit(String sectionName, String sectionJson, Function<String, String> callFn) {
        if ("otherInfo".equals(sectionName)) {
            String input = StringUtils.hasText(sectionJson) ? sectionJson : "{}";
            if (input.length() > MAX_SECTION_LENGTH) {
                return "{\"message\":\"" + sectionName + "超长，llm未处理\"}";
            }
            return callFn.apply(input);
        }
        try {
            String input = StringUtils.hasText(sectionJson) ? sectionJson : "[]";
            if (!"pat_illnessCourse".equals(sectionName)) {
                if (input.length() > MAX_SECTION_LENGTH) {
                    return "{\"message\":\"" + sectionName + "超长，llm未处理\"}";
                }
                return callFn.apply(input);
            }
            JsonNode node = objectMapper.readTree(input);
            List<String> chunks = new ArrayList<>();
            for (JsonNode chunkNode : node) {
                chunks.add(toJson(chunkNode));
            }
            if (chunks.isEmpty()) {
                chunks = List.of("{}");
            }

            for (String chunk : chunks) {
                if (chunk.length() > MAX_SECTION_LENGTH) {
                    return "{\"message\":\"" + sectionName + "超长，llm未处理\"}";
                }
            }

            List<JsonNode> partialNodes = new ArrayList<>();
            for (String chunk : chunks) {
                String partial = callFn.apply(chunk);
                partialNodes.add(parseToNode(partial));
            }
            JsonNode merged = mergePartialNodes(partialNodes);
            return toJson(merged);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

    }

    public static String processSplitAndMerge(String illnessSectionName,
                                       String otherSectionName,
                                       String illnessJson,
                                       String otherJson,
                                       Function<String, String> illnessCallFn,
                                       Function<String, String> otherCallFn,
                                       Function<String, String> mergeCallFn) {
        String illnessPart = formatSectionWithSplit(illnessSectionName, illnessJson, illnessCallFn);
        String otherPart = formatSectionWithSplit(otherSectionName, otherJson, otherCallFn);
        String finalInput = toJson(prepareMergeInput(illnessPart, otherPart));
        return mergeCallFn.apply(finalInput);
    }

    /**
     * 将对象转换为JSON字符串
     * @param obj 要转换的对象
     * @return JSON字符串
     */
    public static String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 将JSON字符串解析为JsonNode
     * @param json JSON字符串
     * @return JsonNode对象
     */
    public static JsonNode parseToNode(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private static List<String> splitJsonForLlm(String json, int targetLen) {
        if (!StringUtils.hasText(json)) {
            return List.of("{}");
        }
        /*if (json.length() <= targetLen) {
            return List.of(json);
        }*/
        try {
            JsonNode node = objectMapper.readTree(json);
//            List<JsonNode> chunks = splitNode(node, targetLen);
            List<String> result = new ArrayList<>();
            for (JsonNode chunkNode : node) {
                result.add(toJson(chunkNode));
            }
            return result;
        } catch (Exception e) {
            List<String> rawChunks = new ArrayList<>();
            for (int i = 0; i < json.length(); i += targetLen) {
                int end = Math.min(i + targetLen, json.length());
                rawChunks.add(json.substring(i, end));
            }
            return rawChunks;
        }
    }

    private List<JsonNode> splitNode(JsonNode node, int targetLen) {
        if (toJson(node).length() <= targetLen) {
            return List.of(node);
        }
        if (node.isArray()) {
            return splitArrayNode((ArrayNode) node, targetLen);
        }
        if (node.isObject()) {
            return splitObjectNode((ObjectNode) node, targetLen);
        }
        return splitTextNode(node.asText(""), targetLen);
    }

    private List<JsonNode> splitArrayNode(ArrayNode arrayNode, int targetLen) {
        List<JsonNode> result = new ArrayList<>();
        ArrayNode current = objectMapper.createArrayNode();
        for (JsonNode element : arrayNode) {
            ArrayNode probe = current.deepCopy();
            probe.add(element);
            if (probe.toString().length() <= targetLen) {
                current = probe;
                continue;
            }
            if (current.size() > 0) {
                result.add(current);
                current = objectMapper.createArrayNode();
            }
            if (toJson(element).length() <= targetLen) {
                current.add(element);
            } else {
                result.addAll(splitNode(element, targetLen));
            }
        }
        if (current.size() > 0) {
            result.add(current);
        }
        return result;
    }

    private List<JsonNode> splitObjectNode(ObjectNode objectNode, int targetLen) {
        List<JsonNode> result = new ArrayList<>();
        ObjectNode current = objectMapper.createObjectNode();
        Iterator<String> fieldNames = objectNode.fieldNames();
        while (fieldNames.hasNext()) {
            String key = fieldNames.next();
            JsonNode value = objectNode.get(key);

            ObjectNode probe = current.deepCopy();
            probe.set(key, value);
            if (probe.toString().length() <= targetLen) {
                current = probe;
                continue;
            }
            if (current.size() > 0) {
                result.add(current);
                current = objectMapper.createObjectNode();
            }

            ObjectNode single = objectMapper.createObjectNode();
            single.set(key, value);
            if (single.toString().length() <= targetLen) {
                current.set(key, value);
            } else {
                List<JsonNode> splitValues = splitNode(value, targetLen);
                int index = 1;
                for (JsonNode subValue : splitValues) {
                    ObjectNode part = objectMapper.createObjectNode();
                    String partKey = splitValues.size() == 1 ? key : key + "_part_" + index++;
                    part.set(partKey, subValue);
                    result.add(part);
                }
            }
        }
        if (current.size() > 0) {
            result.add(current);
        }
        return result;
    }

    private List<JsonNode> splitTextNode(String text, int targetLen) {
        List<JsonNode> result = new ArrayList<>();
        for (int i = 0; i < text.length(); i += targetLen) {
            int end = Math.min(i + targetLen, text.length());
            ObjectNode node = objectMapper.createObjectNode();
            node.put("text_part", text.substring(i, end));
            result.add(node);
        }
        return result;
    }

    private static JsonNode mergePartialNodes(List<JsonNode> partialNodes) {
        ObjectNode merged = objectMapper.createObjectNode();
        for (JsonNode node : partialNodes) {
            if (node == null || !node.isObject()) {
                continue;
            }
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String key = names.next();
                JsonNode value = node.get(key);
                if (value != null && value.isArray()) {
                    ArrayNode target = merged.has(key) && merged.get(key).isArray()
                            ? (ArrayNode) merged.get(key)
                            : objectMapper.createArrayNode();
                    for (JsonNode item : value) {
                        if (!containsArrayItem(target, item)) {
                            target.add(item);
                        }
                    }
                    merged.set(key, target);
                } else if (!merged.has(key)) {
                    merged.set(key, value);
                }
            }
        }
        return merged;
    }

    private static boolean containsArrayItem(ArrayNode arrayNode, JsonNode item) {
        for (JsonNode existing : arrayNode) {
            if (existing.equals(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按句子去重
     * @param firstText 首次病例文本
     * @param currentText 需要去重的病例文本
     * @return 去重后的文本
     */
    public static String removeDuplicateSentences(String firstText, String currentText) {

        if (firstText == null) firstText = "";
        if (currentText == null) currentText = "";

        // 分句
        Set<String> firstSentences = splitSentences(firstText);
        Set<String> currentSentences = splitSentences(currentText);

        StringBuilder result = new StringBuilder();

        for (String sentence : currentSentences) {
            if (!firstSentences.contains(sentence)) {
                result.append(sentence).append("。");
            }
        }

        return result.toString().trim();
    }

    /**
     * 按中文标点分句
     */
    private static Set<String> splitSentences(String text) {

        Set<String> sentences = new LinkedHashSet<>();

        String[] arr = text.split("[。！？；\\n\\r]");

        for (String s : arr) {
            String sentence = s.trim();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
        }

        return sentences;
    }

    public static String normalizeToJson(String modelOutput) {
        if (!StringUtils.hasText(modelOutput)) {
            return "{}";
        }
        String trimmed = modelOutput.trim();
        try {
            objectMapper.readTree(trimmed);
            return trimmed;
        } catch (Exception ignored) {
            int start = trimmed.indexOf('{');
            int end = trimmed.lastIndexOf('}');
            if (start >= 0 && end > start) {
                String candidate = trimmed.substring(start, end + 1);
                try {
                    objectMapper.readTree(candidate);
                    return candidate;
                } catch (Exception e) {
                    log.warn("格式化模型返回非JSON，保留原文");
                }
            }
            return trimmed;
        }
    }
}
