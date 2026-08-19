package com.ism.service;

import com.ism.model.Difference;
import com.ism.model.MatchResult;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class TransliterationMatcher {

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_LETTERS = Pattern.compile("[^a-z']+");
    private static final Set<Character> VOWELS = Set.of('a', 'e', 'i', 'o', 'u');

    public MatchResult match(String rawA, String rawB) {
        String normalizedA = normalize(rawA);
        String normalizedB = normalize(rawB);

        String comparisonA = normalizeComponents(normalizeEnding(normalizedA));
        String comparisonB = normalizeComponents(normalizeEnding(normalizedB));

        String frameA = consonantFrame(comparisonA);
        String frameB = consonantFrame(comparisonB);

        double editSimilarity = similarity(comparisonA, comparisonB);
        double frameSimilarity = similarity(frameA, frameB);
        double lengthSimilarity = 1.0 - (Math.abs(comparisonA.length() - comparisonB.length())
                / (double) Math.max(Math.max(comparisonA.length(), comparisonB.length()), 1));
        double vowelCompatibility = vowelCompatibility(comparisonA, comparisonB, frameA, frameB);

        double score = (0.50 * frameSimilarity)
                + (0.28 * editSimilarity)
                + (0.10 * lengthSimilarity)
                + (0.12 * vowelCompatibility);

        List<String> reasons = new ArrayList<>();
        List<Difference> differences = new ArrayList<>();

        if (normalizedA.equals(normalizedB)) {
            score = 1.0;
            reasons.add("The normalized spellings are identical.");
        } else {
            if (frameA.equals(frameB) && !frameA.isBlank()) {
                reasons.add("Core consonant structure matches: " + readableFrame(frameA) + ".");
            } else if (frameSimilarity >= 0.75) {
                reasons.add("Core consonant structure is strongly aligned.");
            }

            detectVowelVariation(comparisonA, comparisonB, frameA, frameB, reasons, differences);
            detectGemination(normalizedA, normalizedB, reasons, differences);
            detectArticleOrAbdVariation(normalizedA, normalizedB, comparisonA, comparisonB, reasons, differences);
            detectTerminalAhVariation(normalizedA, normalizedB, reasons, differences);
            detectRegionalCandidate(frameA, frameB, reasons, differences);
        }

        // Guardrail: the same Arabic consonantal root does not guarantee the same personal name.
        // A low whole-string similarity cannot become a positive match only because consonants align.
        if (editSimilarity < 0.60 && !normalizedA.equals(normalizedB)) {
            score = Math.min(score, 0.74);
            reasons.add("Whole-name spelling divergence is too large for a high-confidence match.");
        }

        int hardConflicts = hardConsonantConflicts(frameA, frameB);
        if (hardConflicts > 0) {
            score -= Math.min(0.24, hardConflicts * 0.12);
            reasons.add(hardConflicts + " high-weight consonant conflict" + (hardConflicts == 1 ? " was" : "s were") + " detected.");
            differences.add(new Difference("CONSONANT_CONFLICT", frameA, frameB,
                    "High-value consonant substitutions reduce confidence."));
        } else if (!frameA.isBlank() && !frameB.isBlank()) {
            reasons.add("No unexplained high-weight consonant conflict was detected.");
        }

        score = clamp(score);
        String band = score >= 0.82 ? "LIKELY_SAME" : score >= 0.68 ? "POSSIBLE" : "UNLIKELY";
        boolean match = score >= 0.82;

        if (reasons.isEmpty()) {
            reasons.add("The spellings do not provide enough shared transliteration evidence.");
        }

        return new MatchResult(
                match,
                round(score),
                band,
                comparisonA,
                comparisonB,
                readableFrame(frameA),
                readableFrame(frameB),
                List.copyOf(reasons),
                List.copyOf(differences)
        );
    }

    String normalize(String input) {
        String s = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        s = s.replace('’', '\'').replace('‘', '\'').replace('ʾ', '\'').replace('ʿ', '\'');
        s = Normalizer.normalize(s, Normalizer.Form.NFKD);
        s = COMBINING_MARKS.matcher(s).replaceAll("");
        s = NON_LETTERS.matcher(s).replaceAll("");
        return s.replace("'", "");
    }

    String normalizeComponents(String s) {
        String out = s;
        // Common real-world renderings of Abd + al when written as one Latin token.
        out = out.replaceFirst("^abd(?:ul|el|il)", "abdal");
        // Keep standard al- and common el- article spellings comparable at the start of a name component.
        out = out.replaceFirst("^el(?=[a-z])", "al");
        return out;
    }

    String normalizeEnding(String s) {
        // Conservative personal-name handling for common final -a / -ah renderings.
        if (s.length() > 3 && s.endsWith("ah")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }

    String consonantFrame(String s) {
        String protectedDigraphs = s
                .replace("kh", "X")
                .replace("gh", "G")
                .replace("sh", "S")
                .replace("th", "T")
                .replace("dh", "D");

        StringBuilder frame = new StringBuilder();
        for (int i = 0; i < protectedDigraphs.length(); i++) {
            char c = protectedDigraphs.charAt(i);
            if (Character.isUpperCase(c)) {
                appendCollapsed(frame, c);
                continue;
            }
            if (c == 'y') {
                // Initial y is normally consonantal in names such as Yusuf/Yousef.
                if (i == 0) appendCollapsed(frame, c);
                continue;
            }
            if (c == 'w') {
                // Treat w as consonantal unless surrounded by vowels.
                boolean leftVowel = i > 0 && isVowel(protectedDigraphs.charAt(i - 1));
                boolean rightVowel = i + 1 < protectedDigraphs.length() && isVowel(protectedDigraphs.charAt(i + 1));
                if (!(leftVowel && rightVowel)) appendCollapsed(frame, c);
                continue;
            }
            if (!isVowel(c)) appendCollapsed(frame, c);
        }
        return frame.toString();
    }

    private void appendCollapsed(StringBuilder sb, char c) {
        if (sb.length() == 0 || sb.charAt(sb.length() - 1) != c) sb.append(c);
    }

    private boolean isVowel(char c) {
        return VOWELS.contains(Character.toLowerCase(c));
    }

    private double vowelCompatibility(String a, String b, String frameA, String frameB) {
        if (frameA.equals(frameB)) return 1.0;
        if (similarity(frameA, frameB) >= 0.8) return 0.75;
        return 0.35;
    }

    private void detectVowelVariation(String a, String b, String frameA, String frameB,
                                      List<String> reasons, List<Difference> differences) {
        if (!a.equals(b) && frameA.equals(frameB) && !frameA.isBlank()) {
            reasons.add("Differences are concentrated in Latin vowel rendering rather than the consonant frame.");
            differences.add(new Difference("VOWEL_VARIATION", extractVowels(a), extractVowels(b),
                    "Arabic short vowels are not represented uniformly across Latin spellings."));
        }
    }

    private void detectGemination(String a, String b, List<String> reasons, List<Difference> differences) {
        String ca = collapseRepeatedLetters(a);
        String cb = collapseRepeatedLetters(b);
        if (!a.equals(b) && ca.equals(cb)) {
            reasons.add("Repeated consonant length differs, but the collapsed spelling is the same.");
            differences.add(new Difference("GEMINATION", a, b,
                    "Consonant doubling can vary in personal-name romanization."));
        }
    }

    private void detectArticleOrAbdVariation(String originalA, String originalB, String a, String b,
                                             List<String> reasons, List<Difference> differences) {
        boolean abdVariant = (originalA.startsWith("abdul") || originalA.startsWith("abdel") || originalA.startsWith("abdil"))
                && (originalB.startsWith("abdul") || originalB.startsWith("abdel") || originalB.startsWith("abdil") || originalB.startsWith("abdal"));
        abdVariant = abdVariant || ((originalB.startsWith("abdul") || originalB.startsWith("abdel") || originalB.startsWith("abdil"))
                && originalA.startsWith("abdal"));

        if (abdVariant && a.startsWith("abdal") && b.startsWith("abdal")) {
            reasons.add("The Abd + article component is preserved despite a common Latin spelling variation.");
            differences.add(new Difference("NAME_COMPONENT", originalA, originalB,
                    "Abd al- commonly appears in concatenated spellings such as Abdul- or Abdel-."));
        } else if ((originalA.startsWith("al") && originalB.startsWith("el"))
                || (originalA.startsWith("el") && originalB.startsWith("al"))) {
            reasons.add("al- / el- article spelling variation was normalized.");
            differences.add(new Difference("ARTICLE", originalA.substring(0, 2), originalB.substring(0, 2),
                    "al- is the formal article transliteration; el- is common in some real-world name spellings."));
        }
    }

    private void detectTerminalAhVariation(String a, String b, List<String> reasons, List<Difference> differences) {
        if ((a.endsWith("ah") && b.endsWith("a")) || (b.endsWith("ah") && a.endsWith("a"))) {
            reasons.add("Final -a / -ah was treated as a low-cost ending variation.");
            differences.add(new Difference("FINAL_AH", ending(a), ending(b),
                    "This is a conservative name-spelling rule, not a universal Arabic-letter substitution."));
        }
    }

    private void detectRegionalCandidate(String frameA, String frameB, List<String> reasons, List<Difference> differences) {
        if (frameA.length() != frameB.length() || frameA.isBlank()) return;
        for (int i = 0; i < frameA.length(); i++) {
            char a = Character.toLowerCase(frameA.charAt(i));
            char b = Character.toLowerCase(frameB.charAt(i));
            if (a == b) continue;
            if (isRegionalPair(a, b)) {
                reasons.add("A possible regional consonant rendering was detected, but it is not treated as automatic equivalence.");
                differences.add(new Difference("REGIONAL_CANDIDATE", String.valueOf(a), String.valueOf(b),
                        "q/g and j/g variation can be dialect-sensitive, so Ism keeps a confidence penalty."));
                return;
            }
        }
    }

    private boolean isRegionalPair(char a, char b) {
        return (a == 'q' && b == 'g') || (a == 'g' && b == 'q')
                || (a == 'j' && b == 'g') || (a == 'g' && b == 'j');
    }

    private int hardConsonantConflicts(String a, String b) {
        if (a.equals(b)) return 0;
        if (a.length() != b.length()) return Math.max(1, levenshtein(a, b));
        int conflicts = 0;
        for (int i = 0; i < a.length(); i++) {
            char x = Character.toLowerCase(a.charAt(i));
            char y = Character.toLowerCase(b.charAt(i));
            if (x != y && !isRegionalPair(x, y)) conflicts++;
        }
        return conflicts;
    }

    private String extractVowels(String s) {
        StringBuilder out = new StringBuilder();
        for (char c : s.toCharArray()) if (isVowel(c)) out.append(c);
        return out.toString();
    }

    private String collapseRepeatedLetters(String s) {
        StringBuilder out = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (out.length() == 0 || out.charAt(out.length() - 1) != c) out.append(c);
        }
        return out.toString();
    }

    private String ending(String s) {
        if (s.endsWith("ah")) return "ah";
        if (s.endsWith("a")) return "a";
        return "";
    }

    private String readableFrame(String frame) {
        return frame.replace("X", "KH")
                .replace("G", "GH")
                .replace("S", "SH")
                .replace("T", "TH")
                .replace("D", "DH")
                .toUpperCase(Locale.ROOT);
    }

    private double similarity(String a, String b) {
        int max = Math.max(a.length(), b.length());
        if (max == 0) return 1.0;
        return 1.0 - (levenshtein(a, b) / (double) max);
    }

    private int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[b.length()];
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
