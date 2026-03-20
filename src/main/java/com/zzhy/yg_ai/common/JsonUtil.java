package com.zzhy.yg_ai.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JSON 处理工具类
 * 提供 JSON 格式校验、字符串转换、键值验证等功能
 *
 * @author AI Assistant
 * @date 2026-03-07
 */
public class JsonUtil {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建JSON对象
     */
    public static ObjectNode createObjectNode() {
        return objectMapper.createObjectNode();
    }

    /**
     * 校验字符串是否为有效的 JSON 格式
     *
     * @param jsonString 待校验的 JSON 字符串
     * @return true-有效的 JSON 格式，false-无效的 JSON 格式
     */
    public static boolean isValidJson(String jsonString) {
        if (jsonString == null || jsonString.trim().isEmpty()) {
            return false;
        }
        try {
            objectMapper.readTree(jsonString);
            return true;
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    /**
     * 将字符串转换为 JSON 格式
     * 自动处理单引号替换为双引号的情况
     *
     * @param jsonString 待转换的字符串（可以是单引号或双引号的 JSON）
     * @return 标准的 JSON 字符串（双引号格式）
     * @throws JsonException 当字符串无法转换为 JSON 时抛出此异常
     */
    public static String toJson(String jsonString) throws JsonException {
        if (jsonString == null || jsonString.trim().isEmpty()) {
            throw new JsonException("输入字符串为空");
        }

        // 去除首尾空白字符
        String trimmed = jsonString.trim();
        
        // 如果已经是有效的 JSON，直接返回
        if (isValidJson(trimmed)) {
            return trimmed;
        }

        // 尝试将单引号替换为双引号
//        String converted = convertSingleQuotesToDouble(trimmed);
        
        if (isValidJson(trimmed)) {
            return trimmed;
        }

        throw new JsonException("无法将字符串转换为有效的 JSON 格式：" + jsonString);
    }

    /**
     * 将字符串转换为 JsonNode 对象
     *
     * @param jsonString JSON 字符串
     * @return JsonNode 对象
     * @throws JsonException 当字符串不是有效的 JSON 时抛出此异常
     */
    public static JsonNode toJsonNode(String jsonString) throws JsonException {
        try {
            String validJson = toJson(jsonString);
            return objectMapper.readTree(validJson);
        } catch (JsonProcessingException e) {
            throw new JsonException("JSON 解析失败：" + e.getMessage(), e);
        }
    }

    /**
     * 校验 JSON 中是否包含所有指定的 key
     *
     * @param jsonString JSON 字符串
     * @param keys 需要校验的 key 列表
     * @return true-所有 key 都存在，false-存在缺失的 key
     */
    public static boolean containsAllKeys(String jsonString, List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return true;
        }

        try {
            JsonNode jsonNode = toJsonNode(jsonString);
            return containsAllKeys(jsonNode, keys);
        } catch (JsonException e) {
            return false;
        }
    }

    /**
     * 校验 JSON 中是否包含所有指定的 key（支持嵌套 key，使用点号分隔）
     *
     * @param jsonString JSON 字符串
     * @param keys 需要校验的 key 列表（支持嵌套，如："user.name"）
     * @return true-所有 key 都存在，false-存在缺失的 key
     */
    public static boolean containsAllKeysWithNested(String jsonString, List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return true;
        }

        try {
            JsonNode jsonNode = toJsonNode(jsonString);
            for (String key : keys) {
                if (!hasNestedKey(jsonNode, key)) {
                    return false;
                }
            }
            return true;
        } catch (JsonException e) {
            return false;
        }
    }

    /**
     * 获取 JSON 中缺失的 key 列表
     *
     * @param jsonString JSON 字符串
     * @param keys 需要校验的 key 列表
     * @return 缺失的 key 列表
     */
    public static Set<String> getMissingKeys(String jsonString, List<String> keys) {
        java.util.HashSet<String> missingKeys = new java.util.HashSet<>();
        if (keys == null || keys.isEmpty()) {
            return missingKeys;
        }

        try {
            JsonNode jsonNode = toJsonNode(jsonString);
            for (String key : keys) {
                if (!hasNestedKey(jsonNode, key)) {
                    missingKeys.add(key);
                }
            }
        } catch (JsonException e) {
            // 如果 JSON 解析失败，返回所有 key 都缺失
            missingKeys.addAll(keys);
        }

        return missingKeys;
    }

    /**
     * 将对象转换为 JSON 字符串
     *
     * @param object 待转换的对象
     * @return JSON 字符串
     * @throws JsonException 当转换失败时抛出此异常
     */
    public static String toJsonString(Object object) throws JsonException {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            throw new JsonException("对象转 JSON 失败：" + e.getMessage(), e);
        }
    }

    /**
     * 将 JSON 字符串转换为指定类型的对象
     *
     * @param jsonString JSON 字符串
     * @param clazz 目标类型
     * @param <T> 泛型
     * @return 转换后的对象
     * @throws JsonException 当转换失败时抛出此异常
     */
    public static <T> T fromJson(String jsonString, Class<T> clazz) throws JsonException {
        try {
            String validJson = toJson(jsonString);
            return objectMapper.readValue(validJson, clazz);
        } catch (JsonProcessingException e) {
            throw new JsonException("JSON 转对象失败：" + e.getMessage(), e);
        }
    }

    /**
     * 将 JSON 字符串转换为 Map
     *
     * @param jsonString JSON 字符串
     * @return Map 对象
     * @throws JsonException 当转换失败时抛出此异常
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(String jsonString) throws JsonException {
        return fromJson(jsonString, Map.class);
    }

    /**
     * 美化 JSON 输出（带缩进）
     *
     * @param jsonString JSON 字符串
     * @return 格式化后的 JSON 字符串
     * @throws JsonException 当格式化失败时抛出此异常
     */
    public static String prettyPrint(String jsonString) throws JsonException {
        try {
            JsonNode jsonNode = toJsonNode(jsonString);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode);
        } catch (JsonProcessingException e) {
            throw new JsonException("JSON 格式化失败：" + e.getMessage(), e);
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 将单引号转换为双引号
     * 注意：只转换 JSON 结构中的引号，不转换字符串值中的引号
     */
    private static String convertSingleQuotesToDouble(String jsonString) {
        // 简单场景：直接替换所有单引号为双引号
        // 注意：这种方法在某些特殊情况下可能不适用（如值中包含单引号）
        return jsonString.replace("'", "\"");
    }

    /**
     * 校验 JsonNode 中是否包含所有指定的 key
     */
    private static boolean containsAllKeys(JsonNode jsonNode, List<String> keys) {
        if (!jsonNode.isObject()) {
            return false;
        }

        ObjectNode objectNode = (ObjectNode) jsonNode;
        for (String key : keys) {
            if (!objectNode.has(key)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查是否存在嵌套的 key（支持点号分隔的路径）
     * 例如："user.address.city"
     */
    private static boolean hasNestedKey(JsonNode jsonNode, String keyPath) {
        if (keyPath.contains(".")) {
            String[] keys = keyPath.split("\\.");
            JsonNode currentNode = jsonNode;
            
            for (String key : keys) {
                if (currentNode == null || !currentNode.isObject() || !currentNode.has(key)) {
                    return false;
                }
                currentNode = currentNode.get(key);
            }
            return true;
        } else {
            return jsonNode.isObject() && jsonNode.has(keyPath);
        }
    }

    /**
     * 自定义 JSON 处理异常类
     */
    public static class JsonException extends Exception {
        public JsonException(String message) {
            super(message);
        }

        public JsonException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
