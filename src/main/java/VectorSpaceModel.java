import java.util.*;

public class VectorSpaceModel {
    private final Map<String, Map<Integer, Integer>> termFrequencies; // term -> {docId -> frequency}
    private final Map<String, Double> inverseDocFreq; // term -> idf
    private final Map<Integer, Map<String, List<Integer>>> wordPositions; // docId -> {term -> positions}
    private final Map<Integer, String> titles; // docId -> title
    private final int totalDocuments;
    private final Porter stemmer;

    public VectorSpaceModel(Map<String, Map<Integer, Integer>> termFreq, 
                          Map<Integer, Map<String, List<Integer>>> positions,
                          Map<Integer, String> titles) {
        this.termFrequencies = termFreq;
        this.wordPositions = positions;
        this.titles = titles;
        this.totalDocuments = titles.size();
        this.stemmer = new Porter();
        this.inverseDocFreq = calculateIDF();
    }

    private Map<String, Double> calculateIDF() {
        Map<String, Double> idf = new HashMap<>();
        for (String term : termFrequencies.keySet()) {
            int docFreq = termFrequencies.get(term).size();
            idf.put(term, Math.log((double) totalDocuments / docFreq));
        }
        return idf;
    }

    private double getMaxTF(int docId) {
        double maxTF = 0;
        for (Map<Integer, Integer> docFreqs : termFrequencies.values()) {
            Integer freq = docFreqs.get(docId);
            if (freq != null && freq > maxTF) {
                maxTF = freq;
            }
        }
        return maxTF;
    }

    private Map<String, Double> createDocumentVector(int docId) {
        Map<String, Double> vector = new HashMap<>();
        double maxTF = getMaxTF(docId);
        
        for (String term : termFrequencies.keySet()) {
            Map<Integer, Integer> docFreqs = termFrequencies.get(term);
            Integer freq = docFreqs.get(docId);
            if (freq != null) {
                // tf-idf weight = (tf/max(tf)) * idf
                double weight = (freq / maxTF) * inverseDocFreq.get(term);
                // Boost weight if term appears in title
                if (titles.get(docId).toLowerCase().contains(term)) {
                    weight *= 1.5; // 50% boost for title matches
                }
                vector.put(term, weight);
            }
        }
        return vector;
    }

    private Map<String, Double> createQueryVector(List<String> queryTerms) {
        Map<String, Double> vector = new HashMap<>();
        Map<String, Integer> termCounts = new HashMap<>();
        
        // Count term frequencies in query
        for (String term : queryTerms) {
            term = stemmer.stripAffixes(term.toLowerCase());
            termCounts.merge(term, 1, Integer::sum);
        }
        
        // Calculate query weights
        double maxTF = Collections.max(termCounts.values());
        for (Map.Entry<String, Integer> entry : termCounts.entrySet()) {
            String term = entry.getKey();
            if (inverseDocFreq.containsKey(term)) {
                double tf = entry.getValue() / maxTF;
                vector.put(term, tf * inverseDocFreq.get(term));
            }
        }
        
        return vector;
    }

    private double cosineSimilarity(Map<String, Double> v1, Map<String, Double> v2) {
        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;
        
        for (String key : v1.keySet()) {
            if (v2.containsKey(key)) {
                dotProduct += v1.get(key) * v2.get(key);
            }
            norm1 += v1.get(key) * v1.get(key);
        }
        
        for (double value : v2.values()) {
            norm2 += value * value;
        }
        
        if (norm1 == 0 || norm2 == 0) return 0.0;
        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    private boolean matchesPhrase(int docId, List<String> phrase) {
        if (phrase.size() <= 1) return true;
        
        // Get positions for first word
        String firstWord = stemmer.stripAffixes(phrase.get(0).toLowerCase());
        List<Integer> positions = wordPositions.get(docId).getOrDefault(firstWord, Collections.emptyList());
        
        // For each position of the first word
        for (int pos : positions) {
            boolean matches = true;
            // Check if subsequent words appear in sequence
            for (int i = 1; i < phrase.size(); i++) {
                String nextWord = stemmer.stripAffixes(phrase.get(i).toLowerCase());
                List<Integer> nextPositions = wordPositions.get(docId).getOrDefault(nextWord, Collections.emptyList());
                if (!nextPositions.contains(pos + i)) {
                    matches = false;
                    break;
                }
            }
            if (matches) return true;
        }
        return false;
    }

    public List<Integer> search(String query, Set<String> stopWords) {
        List<List<String>> phrases = parseQuery(query, stopWords);
        Map<Integer, Double> scores = new HashMap<>();
        
        // Process each phrase separately
        for (List<String> phrase : phrases) {
            // Create query vector for this phrase
            Map<String, Double> queryVector = createQueryVector(phrase);
            
            // Score each document
            for (int docId : titles.keySet()) {
                // Skip if document doesn't match phrase exactly
                if (!matchesPhrase(docId, phrase)) continue;
                
                Map<String, Double> docVector = createDocumentVector(docId);
                double similarity = cosineSimilarity(queryVector, docVector);
                
                // Combine scores for multiple phrases
                scores.merge(docId, similarity, Double::sum);
            }
        }
        
        // Sort documents by score
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
                .limit(50) // Return top 50 results
                .map(Map.Entry::getKey)
                .toList();
    }

    private List<List<String>> parseQuery(String query, Set<String> stopWords) {
        List<List<String>> phrases = new ArrayList<>();
        StringBuilder currentPhrase = new StringBuilder();
        boolean inQuotes = false;
        
        // Split query into phrases
        for (char c : query.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
                if (!inQuotes && currentPhrase.length() > 0) {
                    phrases.add(tokenizePhrase(currentPhrase.toString(), stopWords));
                    currentPhrase = new StringBuilder();
                }
            } else if (!inQuotes && Character.isWhitespace(c)) {
                if (currentPhrase.length() > 0) {
                    phrases.add(tokenizePhrase(currentPhrase.toString(), stopWords));
                    currentPhrase = new StringBuilder();
                }
            } else {
                currentPhrase.append(c);
            }
        }
        
        if (currentPhrase.length() > 0) {
            phrases.add(tokenizePhrase(currentPhrase.toString(), stopWords));
        }
        
        return phrases;
    }

    private List<String> tokenizePhrase(String phrase, Set<String> stopWords) {
        return Arrays.stream(phrase.toLowerCase().split("\\W+"))
                .filter(word -> !stopWords.contains(word))
                .limit(3) // Limit to trigrams
                .toList();
    }
}
