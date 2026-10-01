package com.codex.wordpkhelper;

import android.graphics.Rect;
import com.google.mlkit.vision.text.Text;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class VocabMatcher {
    static final class Match {
        final String word;
        final Rect box;
        Match(String word, Rect box) { this.word = word; this.box = box; }
    }
    static final class Choice {
        final String word;
        final String meaning;
        final Rect box;
        Choice(String word, String meaning, Rect box) {
            this.word = word; this.meaning = meaning; this.box = box;
        }
    }

    private final Map<String, String[]> entries = new LinkedHashMap<>();

    VocabMatcher(android.content.Context context) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("vocab.tsv"), StandardCharsets.UTF_8))) {
            String row;
            while ((row = reader.readLine()) != null) {
                String[] parts = row.split("\\t", 2);
                if (parts.length == 2) entries.put(parts[0].trim().toLowerCase(Locale.ROOT), parts[1].split(";"));
            }
        } catch (Exception ignored) { }
    }

    Match findEnglishPromptWord(List<Text.Line> lines) {
        Match best = null;
        long bestArea = -1;
        int bestWordLength = -1;
        boolean tied = false;
        for (Text.Line line : lines) {
            Rect bounds = line.getBoundingBox();
            if (bounds == null) continue;
            String text = normalizeEnglish(line.getText());
            for (String word : entries.keySet()) {
                if (!containsEnglishPhrase(text, word)) continue;
                long area = (long) bounds.width() * bounds.height();
                // Prefer the full vocabulary item when one entry is a prefix of another
                // (for example, "first-aid kit" and "first-aid").
                if (area > bestArea || (area == bestArea && word.length() > bestWordLength)) {
                    bestArea = area;
                    bestWordLength = word.length();
                    best = new Match(word, bounds);
                    tied = false;
                } else if (area == bestArea && word.length() == bestWordLength
                        && best != null && !best.word.equals(word)) {
                    tied = true;
                }
            }
        }
        return tied ? null : best;
    }

    Choice findBestEnglishOption(List<Text.Line> questionLines, List<Text.Line> optionLines, int optionsTop) {
        StringBuilder promptBuilder = new StringBuilder();
        questionLines.stream()
                .sorted((a, b) -> {
                    Rect ra = a.getBoundingBox(), rb = b.getBoundingBox();
                    return Integer.compare(ra == null ? 0 : ra.top, rb == null ? 0 : rb.top);
                })
                .forEach(line -> promptBuilder.append(normalizeChinese(line.getText())));
        String prompt = promptBuilder.toString();
        if (prompt.length() < 3) return null;

        Map<String, Rect> detectedOptions = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (Text.Line line : optionLines) {
            Rect bounds = line.getBoundingBox();
            if (bounds == null) continue;
            String option = normalizeEnglish(line.getText());
            String key = option + "@" + (bounds.centerY() / Math.max(1, bounds.height() * 2));
            if (!seen.add(key)) continue;
            List<String> matches = new ArrayList<>();
            for (String word : entries.keySet()) {
                if (containsEnglishPhrase(option, word)) matches.add(word);
            }
            // An OCR line may contain a complete phrase as well as a shorter entry
            // within it. Keep only the longest match for that displayed option.
            int longestMatch = matches.stream().mapToInt(String::length).max().orElse(0);
            for (String word : matches) {
                if (word.length() == longestMatch) detectedOptions.put(word, bounds);
            }
        }

        List<ScoredOption> scored = new ArrayList<>();
        for (Map.Entry<String, Rect> option : detectedOptions.entrySet()) {
            int score = 0;
            StringBuilder combinedMeanings = new StringBuilder();
            for (String rawMeaning : entries.get(option.getKey())) {
                String meaning = normalizeChinese(rawMeaning);
                combinedMeanings.append(meaning);
                if (meaning.length() < 2) continue;
                if (prompt.contains(meaning)) {
                    score = Math.max(score, 1000 + meaning.length() * 10);
                } else if (meaning.contains(prompt) && prompt.length() >= 4) {
                    score = Math.max(score, 800 + prompt.length() * 10);
                } else if (meaning.length() >= 4) {
                    int overlap = longestCommonSubsequence(prompt, meaning);
                    double ratio = overlap / (double) meaning.length();
                    if (ratio >= 0.75) score = Math.max(score, 500 + overlap * 10);
                }
            }
            // The PK screen can display several short synonyms together and truncate
            // the end with an ellipsis (e.g. "考虑；深思；想法；看法；..."). Compare
            // that visible prefix against the vocabulary item's full synonym list.
            if (prompt.length() >= 3 && combinedMeanings.indexOf(prompt) >= 0) {
                score = Math.max(score, 900 + prompt.length() * 10);
            }
            if (score > 0) scored.add(new ScoredOption(option.getKey(), option.getValue(), score));
        }
        scored.sort((a, b) -> Integer.compare(b.score, a.score));
        if (scored.isEmpty()) return null;
        if (scored.size() > 1 && scored.get(0).score - scored.get(1).score < 10) return null;
        Rect screenBox = new Rect(scored.get(0).box);
        screenBox.offset(0, optionsTop);
        return new Choice(scored.get(0).word, scored.get(0).word, screenBox);
    }

    Choice findBestChineseOption(String word, List<Text.Line> optionLines, int optionsTop) {
        String[] meanings = entries.get(word.toLowerCase(Locale.ROOT));
        if (meanings == null) return null;
        List<ScoredOption> scored = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Text.Line line : optionLines) {
            Rect bounds = line.getBoundingBox();
            if (bounds == null) continue;
            String option = normalizeChinese(line.getText());
            if (option.isEmpty()) continue;
            String key = option + "@" + (bounds.centerY() / Math.max(1, bounds.height() * 2));
            if (!seen.add(key)) continue;
            int score = 0;
            String selectedMeaning = null;
            for (String rawMeaning : meanings) {
                String meaning = normalizeChinese(rawMeaning);
                if (meaning.isEmpty()) continue;
                if (option.contains(meaning)) {
                    int current = 1000 + meaning.length() * 10;
                    if (current > score) { score = current; selectedMeaning = rawMeaning; }
                } else if (meaning.contains(option) && option.length() >= 2) {
                    int current = 800 + option.length() * 10;
                    if (current > score) { score = current; selectedMeaning = rawMeaning; }
                } else if (meaning.length() >= 4) {
                    int overlap = longestCommonSubsequence(option, meaning);
                    if (overlap / (double) meaning.length() >= 0.75) {
                        int current = 500 + overlap * 10;
                        if (current > score) { score = current; selectedMeaning = rawMeaning; }
                    }
                }
            }
            if (score > 0) scored.add(new ScoredOption(selectedMeaning, bounds, score));
        }
        scored.sort((a, b) -> Integer.compare(b.score, a.score));
        if (scored.isEmpty()) return null;
        if (scored.size() > 1 && scored.get(0).score - scored.get(1).score < 10) return null;
        Rect screenBox = new Rect(scored.get(0).box);
        screenBox.offset(0, optionsTop);
        return new Choice(word, scored.get(0).word, screenBox);
    }

    private static final class ScoredOption {
        final String word;
        final Rect box;
        final int score;
        ScoredOption(String word, Rect box, int score) { this.word = word; this.box = box; this.score = score; }
    }

    private int longestCommonSubsequence(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                dp[i][j] = a.charAt(i - 1) == b.charAt(j - 1)
                        ? dp[i - 1][j - 1] + 1
                        : Math.max(dp[i - 1][j], dp[i][j - 1]);
            }
        }
        return dp[a.length()][b.length()];
    }

    String meanings(String word) {
        String[] values = entries.get(word.toLowerCase(Locale.ROOT));
        if (values == null) return "";
        return android.text.TextUtils.join("；", values);
    }

    private String normalizeChinese(String value) {
        return value == null ? "" : value.replaceAll("[^\\u3400-\\u9FFF]", "");
    }

    private String normalizeEnglish(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z]+", " ").trim().replaceAll("\\s+", " ");
    }

    private boolean containsEnglishPhrase(String normalizedText, String vocabularyWord) {
        String phrase = normalizeEnglish(vocabularyWord);
        if (phrase.isEmpty()) return false;
        return (" " + normalizedText + " ").contains(" " + phrase + " ");
    }
}
