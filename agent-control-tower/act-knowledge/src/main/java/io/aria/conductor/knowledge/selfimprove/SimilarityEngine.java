package io.aria.conductor.knowledge.selfimprove;

import io.aria.conductor.common.model.KnowledgeItem;
import io.aria.conductor.common.model.PromptCall;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * In-memory cosine similarity engine using simple TF-IDF vectors.
 * <p>
 * Suitable for {@code <10K} prompt calls in the local MVP — no external
 * vector store. Tokenisation is whitespace + lower-case; stop words are
 * removed; no stemming. The implementation is purposefully dependency-free
 * (pure JDK).
 */
@Service
public class SimilarityEngine {

    /** Minimal English stop-word list — keeps signal in short prompts. */
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from",
            "has", "have", "he", "in", "is", "it", "its", "of", "on", "or",
            "she", "that", "the", "their", "they", "this", "to", "was",
            "were", "will", "with", "you", "your", "i", "we", "us", "our");

    private static final double DUPLICATE_THRESHOLD = 0.95;

    /** Compute cosine similarity in {@code [0,1]} between two raw texts. */
    public double cosineSimilarity(String text1, String text2) {
        if (text1 == null || text2 == null) return 0.0;
        if (text1.isBlank() && text2.isBlank()) return 1.0;
        if (text1.isBlank() || text2.isBlank()) return 0.0;

        Map<String, Integer> tf1 = termFrequencies(text1);
        Map<String, Integer> tf2 = termFrequencies(text2);
        if (tf1.isEmpty() || tf2.isEmpty()) return 0.0;

        // Build IDF over the two-document corpus.
        Set<String> vocab = new HashSet<>(tf1.keySet());
        vocab.addAll(tf2.keySet());
        Map<String, Double> idf = new HashMap<>();
        for (String term : vocab) {
            int df = (tf1.containsKey(term) ? 1 : 0) + (tf2.containsKey(term) ? 1 : 0);
            idf.put(term, Math.log(1.0 + (2.0 / (double) df)));
        }
        Map<String, Double> v1 = weight(tf1, idf);
        Map<String, Double> v2 = weight(tf2, idf);
        double dot = dotProduct(v1, v2);
        double mag = magnitude(v1) * magnitude(v2);
        if (mag == 0.0) return 0.0;
        double sim = dot / mag;
        if (sim < 0.0) return 0.0;
        if (sim > 1.0) return 1.0;
        return sim;
    }

    /**
     * Group prompt calls into clusters where every pair has cosine
     * {@code >= threshold}. Naïve single-link agglomerative clustering on
     * the call's user-prompt-equivalent fingerprint (provider+model+tools
     * +outcome — the executable metadata exposed on {@link PromptCall}).
     */
    public List<List<PromptCall>> findSimilarClusters(List<PromptCall> calls, double threshold) {
        List<List<PromptCall>> clusters = new ArrayList<>();
        if (calls == null || calls.isEmpty()) return clusters;
        boolean[] assigned = new boolean[calls.size()];
        for (int i = 0; i < calls.size(); i++) {
            if (assigned[i]) continue;
            List<PromptCall> cluster = new ArrayList<>();
            cluster.add(calls.get(i));
            assigned[i] = true;
            String fpI = fingerprint(calls.get(i));
            for (int j = i + 1; j < calls.size(); j++) {
                if (assigned[j]) continue;
                if (cosineSimilarity(fpI, fingerprint(calls.get(j))) >= threshold) {
                    cluster.add(calls.get(j));
                    assigned[j] = true;
                }
            }
            clusters.add(cluster);
        }
        return clusters;
    }

    /**
     * True if {@code text} matches any approved item with cosine
     * {@code > 0.95}. Used as the dedup gate before promotion.
     */
    public boolean isDuplicate(String text, List<KnowledgeItem> existingApproved) {
        if (text == null || existingApproved == null) return false;
        for (KnowledgeItem item : existingApproved) {
            String reference = item.getDescription() != null
                    ? item.getName() + " " + item.getDescription()
                    : item.getName();
            if (cosineSimilarity(text, reference) > DUPLICATE_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    // ---- internals -----------------------------------------------------

    /** Build a TF-IDF-weighted vector for a single text. */
    Map<String, Double> buildTfIdfVector(String text) {
        Map<String, Integer> tf = termFrequencies(text);
        Map<String, Double> idf = new HashMap<>();
        for (String term : tf.keySet()) {
            // Single-doc fallback IDF (used by clients calling this helper
            // independently of pairwise similarity).
            idf.put(term, 1.0);
        }
        return weight(tf, idf);
    }

    double dotProduct(Map<String, Double> v1, Map<String, Double> v2) {
        if (v1.size() > v2.size()) {
            Map<String, Double> swap = v1; v1 = v2; v2 = swap;
        }
        double sum = 0.0;
        for (Map.Entry<String, Double> e : v1.entrySet()) {
            Double other = v2.get(e.getKey());
            if (other != null) sum += e.getValue() * other;
        }
        return sum;
    }

    private double magnitude(Map<String, Double> v) {
        double sum = 0.0;
        for (double w : v.values()) sum += w * w;
        return Math.sqrt(sum);
    }

    private Map<String, Double> weight(Map<String, Integer> tf, Map<String, Double> idf) {
        Map<String, Double> out = new HashMap<>();
        for (Map.Entry<String, Integer> e : tf.entrySet()) {
            double w = e.getValue() * idf.getOrDefault(e.getKey(), 1.0);
            out.put(e.getKey(), w);
        }
        return out;
    }

    private Map<String, Integer> termFrequencies(String text) {
        Map<String, Integer> tf = new HashMap<>();
        for (String tok : tokenize(text)) {
            tf.merge(tok, 1, Integer::sum);
        }
        return tf;
    }

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        String[] raw = text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        List<String> out = new ArrayList<>(raw.length);
        for (String t : raw) {
            if (t.isEmpty()) continue;
            if (STOP_WORDS.contains(t)) continue;
            out.add(t);
        }
        return out;
    }

    private String fingerprint(PromptCall call) {
        StringBuilder sb = new StringBuilder();
        if (call.getProvider() != null) sb.append(call.getProvider()).append(' ');
        if (call.getModel() != null) sb.append(call.getModel()).append(' ');
        if (call.getOutcome() != null) sb.append(call.getOutcome()).append(' ');
        if (call.getToolsUsed() != null) {
            // Normalise comma-list → space tokens.
            sb.append(String.join(" ", Arrays.stream(call.getToolsUsed().split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList()));
        }
        return sb.toString();
    }
}
