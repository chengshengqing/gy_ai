package com.zzhy.yg_ai.common;

import java.util.*;
import java.util.regex.Pattern;
import java.io.*;

public class Test {

    // 分词（简单的基于标点和空格的分词）
    private static List<String> tokenize(String text) {
        // 移除标点符号，按空格和标点分割
        Pattern pattern = Pattern.compile("[\\p{Punct}\\s]+");
        String[] tokens = pattern.split(text.toLowerCase());
        return Arrays.asList(tokens);
    }

    // 计算TF-IDF向量
    private static Map<String, Double> computeTFIDF(List<String> tokens, Map<String, Double> idf) {
        Map<String, Double> tf = new HashMap<>();
        // 计算词频
        for (String token : tokens) {
            tf.put(token, tf.getOrDefault(token, 0.0) + 1.0);
        }
        // 归一化
        double total = tokens.size();
        for (Map.Entry<String, Double> entry : tf.entrySet()) {
            entry.setValue(entry.getValue() / total);
        }

        // 乘以IDF
        Map<String, Double> tfidf = new HashMap<>();
        for (Map.Entry<String, Double> entry : tf.entrySet()) {
            String word = entry.getKey();
            double idfValue = idf.getOrDefault(word, 1.0); // 默认IDF为1
            tfidf.put(word, entry.getValue() * idfValue);
        }
        return tfidf;
    }

    // 计算余弦相似度
    private static double cosineSimilarity(Map<String, Double> v1, Map<String, Double> v2) {
        Set<String> allWords = new HashSet<>();
        allWords.addAll(v1.keySet());
        allWords.addAll(v2.keySet());

        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (String word : allWords) {
            double val1 = v1.getOrDefault(word, 0.0);
            double val2 = v2.getOrDefault(word, 0.0);
            dotProduct += val1 * val2;
            norm1 += val1 * val1;
            norm2 += val2 * val2;
        }

        if (norm1 == 0 || norm2 == 0) return 0.0;
        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    public static void main(String[] args) {
        String text1 = "【体格检查】体格检查 T:36.6℃ P:93次/分 R:22次/分 BP:118/70mmHg，发育正常，营养良好，神志清晰，无贫血貌，自动体位，急性病容，表情安静，扶入病房，检查合作。体正常，舌质紫暗，苔少，伸舌自如，脉涩。全身皮肤黏膜无黄染，无肝掌，无蜘蛛痣，全身浅表淋巴结未触及肿大。头颅五官无畸形，眼睑无水肿，巩膜无黄染，双侧瞳孔等大同圆，直径约2.5mm，对光反射灵敏。耳廓无畸形，外耳道顺畅，无异常分泌物。鼻无畸形、中隔偏曲、穿孔、鼻甲肥大阻塞、分泌物、出血，无鼻翼煽动，鼻腔通畅,通气良好，副鼻窦无压痛，口腔无特殊气味，口唇无发绀，口腔粘膜红润,咽部无充血、肿胀，扁桃体无肿大。颈软,颈静脉稍怒张，肝-颈静脉回流征阴性，气管居中，甲状腺无肿大。胸廓对称、无畸形，肋间隙正常，胸壁静脉无曲张，胸壁正常，乳房正常，呼吸动度两侧一致，语颤正常，无胸膜摩擦感。两肺叩诊呈清音，双肺呼吸音稍粗，双下肺闻及少许湿性啰音，双肺无哮鸣音；心前区无隆起，触诊无震颤，心浊音界扩大，心率103次/分，心律绝对不齐，第一心音强弱不等，脉搏短绌，心脏二尖瓣膜听诊区闻及收缩期杂音。腹部平无皮疹，腹壁静脉无曲张，无胃、肠型及蠕动波；腹平软，剑突下轻压痛，无反跳痛，无肌紧张，肝脾未扪及，Murphy征阴性；移动性浊音阴性，肝脏浊音界存在，肝区无叩痛，肾区无叩痛；肠鸣音正常，4次/分，无气过水声。外生殖器未查，肛门直肠未查。脊柱生理弯曲存在，无病理性畸形，活动度正常,棘突无压痛,四肢关节无畸形。双下肢轻度凹陷性水肿，四肢肌力、肌张力正常；生理反射正常，病理反射阴性。";

        String text2 = "【体格检查】体格检查： T:36.6℃ P:93次/分 R:22次/分BP:118mmHg/70mmHg，发育正常，神志清楚,反应正常，面色晦暗，无脱水貌。舌体正常，舌质紫暗，苔少，伸舌自如，脉涩。急性病性面容，自动体位，颈静脉稍怒张，双侧瞳孔等圆等大，直径约2.50毫米，对光反射灵敏。双侧肋间隙正常，两肺叩诊呈清音，双肺呼吸音稍粗，双下肺闻及少许湿性啰音，双肺无哮鸣音；心前区无隆起，触诊无震颤，心浊音界扩大，心率103次/分，心律绝对不齐，第一心音强弱不等，脉搏短绌，心脏二尖瓣膜听诊区闻及收缩期杂音。腹平软，剑突下轻压痛，无反跳痛，无肌紧张，肠鸣音正常。肝、脾未触及，四肢无肌肉萎缩，双下肢轻度凹陷性水肿，四肢肌力及肌张力正常，病理征、脑膜刺激征阴性。";

        // 分词
        List<String> tokens1 = tokenize(text1);
        List<String> tokens2 = tokenize(text2);

        // 合并所有文档计算IDF
        Map<String, Integer> docFreq = new HashMap<>();
        Set<String> uniqueTokens1 = new HashSet<>(tokens1);
        Set<String> uniqueTokens2 = new HashSet<>(tokens2);

        for (String token : uniqueTokens1) {
            docFreq.put(token, docFreq.getOrDefault(token, 0) + 1);
        }
        for (String token : uniqueTokens2) {
            docFreq.put(token, docFreq.getOrDefault(token, 0) + 1);
        }

        // 计算IDF
        int totalDocs = 2;
        Map<String, Double> idf = new HashMap<>();
        for (Map.Entry<String, Integer> entry : docFreq.entrySet()) {
            idf.put(entry.getKey(), Math.log((double) totalDocs / entry.getValue()));
        }

        // 计算TF-IDF
        Map<String, Double> tfidf1 = computeTFIDF(tokens1, idf);
        Map<String, Double> tfidf2 = computeTFIDF(tokens2, idf);

        // 计算相似度
        double similarity = cosineSimilarity(tfidf1, tfidf2);
        System.out.println("TF-IDF余弦相似度: " + similarity);
        // 预期输出: ~0.85-0.95
    }
}