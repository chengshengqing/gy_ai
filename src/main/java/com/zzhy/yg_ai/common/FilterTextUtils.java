package com.zzhy.yg_ai.common;

import edu.stanford.nlp.pipeline.CoreDocument;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
public class FilterTextUtils {

    private static final double SIMILARITY_THRESHOLD = 0.95D;
    private static final String SPLIT_REGEX = "(?i)(?:\\r?\\n|</?br\\s*/?>)+";
    private static final StanfordCoreNLP SENTENCE_PIPELINE = initPipeline();

    public String filterContent(String firstIllnessCourse, String illnesscontent) {
        if (!StringUtils.hasText(firstIllnessCourse)) {
            return illnesscontent;
        }
        if (!StringUtils.hasText(illnesscontent)) {
            return illnesscontent;
        }

        List<Segment> firstParagraphs = toSegments(splitParagraphs(firstIllnessCourse));
        List<Segment> illnessParagraphs = toSegments(splitParagraphs(illnesscontent));
        SimilarityIndex firstParagraphIndex = buildSimilarityIndex(firstParagraphs);
        List<Segment> keptParagraphs = filterNotSimilar(illnessParagraphs, firstParagraphIndex);

        List<Segment> firstSentences = toSegments(splitByCoreNlpSentence(firstIllnessCourse));
        SimilarityIndex firstSentenceIndex = buildSimilarityIndex(firstSentences);
        List<String> keptSentences = new ArrayList<>();
        for (Segment paragraph : keptParagraphs) {
            List<Segment> sentenceSegments = toSegments(splitByCoreNlpSentence(paragraph.original()));
            for (Segment sentence : filterNotSimilar(sentenceSegments, firstSentenceIndex)) {
                keptSentences.add(sentence.original().trim());
            }
        }
        return String.join("\n", keptSentences);
    }

    private static StanfordCoreNLP initPipeline() {
        Properties props = new Properties();
        props.setProperty("annotators", "tokenize,ssplit");
        props.setProperty("tokenize.options", "ptb3Escaping=false");
        return new StanfordCoreNLP(props);
    }

    private List<String> splitParagraphs(String text) {
        if (!StringUtils.hasText(text)) {
            return Collections.emptyList();
        }
        String[] parts = text.split(SPLIT_REGEX);
        List<String> paragraphs = new ArrayList<>();
        for (String part : parts) {
            String value = part == null ? "" : part.trim();
            if (StringUtils.hasText(value)) {
                paragraphs.add(value);
            }
        }
        return paragraphs;
    }

    private List<String> splitByCoreNlpSentence(String text) {
        if (!StringUtils.hasText(text)) {
            return Collections.emptyList();
        }
        CoreDocument document = new CoreDocument(text);
        synchronized (SENTENCE_PIPELINE) {
            SENTENCE_PIPELINE.annotate(document);
        }
        List<String> sentences = new ArrayList<>();
        document.sentences().forEach(sentence -> {
            String value = sentence.text() == null ? "" : sentence.text().trim();
            if (StringUtils.hasText(value)) {
                sentences.add(value);
            }
        });
        if (sentences.isEmpty()) {
            sentences.add(text.trim());
        }
        return sentences;
    }

    private List<Segment> toSegments(List<String> textList) {
        if (textList == null || textList.isEmpty()) {
            return Collections.emptyList();
        }
        List<Segment> segments = new ArrayList<>(textList.size());
        for (String text : textList) {
            String original = text == null ? "" : text.trim();
            if (!StringUtils.hasText(original)) {
                continue;
            }
            String normalized = normalizeForCompare(original);
            segments.add(new Segment(original, normalized));
        }
        return segments;
    }

    private List<Segment> filterNotSimilar(List<Segment> source, SimilarityIndex baseIndex) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        List<Segment> kept = new ArrayList<>(source.size());
        for (Segment segment : source) {
            if (!baseIndex.hasSimilar(segment.normalized())) {
                kept.add(segment);
            }
        }
        return kept;
    }

    private String normalizeForCompare(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String input = text.trim();
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (Character.isWhitespace(ch)) {
                continue;
            }
            int type = Character.getType(ch);
            if (isPunctuationOrSymbol(type)) {
                continue;
            }
            sb.append(Character.toLowerCase(ch));
        }
        return sb.toString();
    }

    private boolean isPunctuationOrSymbol(int type) {
        return type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.MATH_SYMBOL
                || type == Character.CURRENCY_SYMBOL
                || type == Character.MODIFIER_SYMBOL
                || type == Character.OTHER_SYMBOL;
    }

    private int boundedLevenshteinDistance(String left, String right, int maxDistance) {
        if (left.equals(right)) {
            return 0;
        }
        int leftLength = left.length();
        int rightLength = right.length();
        if (leftLength == 0) {
            return rightLength;
        }
        if (rightLength == 0) {
            return leftLength;
        }
        if (Math.abs(leftLength - rightLength) > maxDistance) {
            return maxDistance + 1;
        }

        int[] previous = new int[rightLength + 1];
        for (int j = 0; j <= rightLength; j++) {
            previous[j] = j;
        }

        int[] current = new int[rightLength + 1];
        int impossible = maxDistance + 1;
        for (int i = 1; i <= leftLength; i++) {
            Arrays.fill(current, impossible);
            current[0] = i;
            int from = Math.max(1, i - maxDistance);
            int to = Math.min(rightLength, i + maxDistance);
            if (from > to) {
                return impossible;
            }
            int minInRow = current[0];
            char leftChar = left.charAt(i - 1);
            for (int j = from; j <= to; j++) {
                int cost = leftChar == right.charAt(j - 1) ? 0 : 1;
                int replace = previous[j - 1] + cost;
                int insert = current[j - 1] + 1;
                int delete = previous[j] + 1;
                int value = Math.min(replace, Math.min(insert, delete));
                current[j] = value;
                if (value < minInRow) {
                    minInRow = value;
                }
            }
            if (minInRow > maxDistance) {
                return impossible;
            }
            int[] temp = previous;
            previous = current;
            current = temp;
        }
        return previous[rightLength];
    }

    private int maxEditDistance(int maxLength) {
        return (int) Math.floor((1.0D - SIMILARITY_THRESHOLD) * maxLength);
    }

    private SimilarityIndex buildSimilarityIndex(List<Segment> segments) {
        Set<String> exactSet = new HashSet<>();
        Map<Integer, List<String>> grouped = new HashMap<>();
        for (Segment segment : segments) {
            String normalized = segment.normalized();
            if (!StringUtils.hasText(normalized)) {
                continue;
            }
            exactSet.add(normalized);
            grouped.computeIfAbsent(normalized.length(), key -> new ArrayList<>()).add(normalized);
        }
        return new SimilarityIndex(exactSet, grouped);
    }

    private record Segment(String original, String normalized) {
    }

    private final class SimilarityIndex {

        private final Set<String> exact;
        private final Map<Integer, List<String>> byLength;

        private SimilarityIndex(Set<String> exact, Map<Integer, List<String>> byLength) {
            this.exact = exact;
            this.byLength = byLength;
        }

        private boolean hasSimilar(String normalizedSource) {
            if (!StringUtils.hasText(normalizedSource)) {
                return false;
            }
            if (exact.contains(normalizedSource)) {
                return true;
            }
            int sourceLength = normalizedSource.length();
            int minLength = (int) Math.ceil(sourceLength * SIMILARITY_THRESHOLD);
            int maxLength = (int) Math.floor(sourceLength / SIMILARITY_THRESHOLD);
            for (int targetLength = minLength; targetLength <= maxLength; targetLength++) {
                List<String> candidates = byLength.get(targetLength);
                if (candidates == null || candidates.isEmpty()) {
                    continue;
                }
                int maxDistance = maxEditDistance(Math.max(sourceLength, targetLength));
                for (String candidate : candidates) {
                    if (Math.abs(sourceLength - candidate.length()) > maxDistance) {
                        continue;
                    }
                    int distance = boundedLevenshteinDistance(normalizedSource, candidate, maxDistance);
                    if (distance <= maxDistance) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
