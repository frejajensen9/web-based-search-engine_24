import jdbm.RecordManager;
import jdbm.RecordManagerFactory;
import jdbm.htree.HTree;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

public class SearchServlet extends HttpServlet {
    private RecordManager recman;
    private HTree pageIndex;
    private HTree invertedIndexBody;
    private HTree invertedIndexTitle;
    private HTree parentChildLinks;
    private Set<String> stopWords;
    private VectorSpaceModel vectorSpaceModel;

    @Override
    public void init() throws ServletException {
        try {
            // Initialize database connection
            recman = RecordManagerFactory.createRecordManager("spider_db");
            
            // Load indexes
            pageIndex = HTree.load(recman, recman.getNamedObject("pageIndex"));
            invertedIndexBody = HTree.load(recman, recman.getNamedObject("invertedIndexBody"));
            invertedIndexTitle = HTree.load(recman, recman.getNamedObject("invertedIndexTitle"));
            parentChildLinks = HTree.load(recman, recman.getNamedObject("parentChildLinks"));
            
            // Load stop words
            stopWords = loadStopWords("src/main/java/stopwords.txt");
            
            // Initialize vector space model
            initializeVectorSpaceModel();
            
        } catch (IOException e) {
            throw new ServletException("Failed to initialize search engine", e);
        }
    }

    private Set<String> loadStopWords(String filename) throws IOException {
        Set<String> words = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = reader.readLine()) != null) {
                words.add(line.trim().toLowerCase());
            }
        }
        return words;
    }

    private void initializeVectorSpaceModel() throws IOException {
        // Build term frequencies map
        Map<String, Map<Integer, Integer>> termFreq = new HashMap<>();
        Map<Integer, Map<String, List<Integer>>> positions = new HashMap<>();
        Map<Integer, String> titles = new HashMap<>();

        // Process inverted index to build term frequencies and positions
        for (String term : getKeys(invertedIndexBody)) {
            Map<Integer, Integer> docFreqs = new HashMap<>();
            for (int docId : (List<Integer>) invertedIndexBody.get(term)) {
                docFreqs.merge(docId, 1, Integer::sum);
                
                // Track word positions
                positions.computeIfAbsent(docId, k -> new HashMap<>())
                        .computeIfAbsent(term, k -> new ArrayList<>())
                        .add(docFreqs.get(docId));
            }
            termFreq.put(term, docFreqs);
        }

        // Get titles for all documents
        for (String url : getKeys(pageIndex)) {
            int docId = (int) pageIndex.get(url);
            String title = (String) invertedIndexTitle.get(docId);
            titles.put(docId, title != null ? title : "Untitled");
        }

        vectorSpaceModel = new VectorSpaceModel(termFreq, positions, titles);
    }

    private List<String> getKeys(HTree tree) throws IOException {
        List<String> keys = new ArrayList<>();
        String key;
        jdbm.helper.FastIterator iter = tree.keys();
        while ((key = (String) iter.next()) != null) {
            keys.add(key);
        }
        return keys;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        String query = request.getParameter("q");
        
        if (query == null || query.trim().isEmpty()) {
            request.getRequestDispatcher("/index.jsp").forward(request, response);
            return;
        }

        // Limit query to 10 keywords
        query = limitQueryLength(query);
        
        // Perform search
        List<Integer> resultIds = vectorSpaceModel.search(query, stopWords);
        List<SearchResult> results = new ArrayList<>();
        
        // Convert results to SearchResult objects
        for (int docId : resultIds) {
            String url = getUrlForDocId(docId);
            String title = (String) invertedIndexTitle.get(docId);
            String lastModified = getLastModificationDate(url);
            int size = getPageSize(url);
            String keywords = getTopKeywords(docId, 5);
            String parentLinks = getParentLinks(docId, 10);
            String childLinks = getChildLinks(docId, 10);
            double score = calculateScore(docId, query);
            
            results.add(new SearchResult(url, title, lastModified, size, 
                                       keywords, parentLinks, childLinks, score));
        }
        
        // Store results in request and forward to JSP
        request.setAttribute("results", results);
        request.getRequestDispatcher("/index.jsp").forward(request, response);
    }

    private String limitQueryLength(String query) {
        String[] words = query.split("\\s+");
        if (words.length <= 10) return query;
        
        StringBuilder limited = new StringBuilder();
        boolean inQuotes = false;
        int wordCount = 0;
        
        for (String word : words) {
            if (wordCount >= 10) break;
            
            if (word.startsWith("\"")) inQuotes = true;
            if (word.endsWith("\"")) inQuotes = false;
            
            if (limited.length() > 0) limited.append(" ");
            limited.append(word);
            
            if (!inQuotes) wordCount++;
        }
        
        return limited.toString();
    }

    private String getUrlForDocId(int docId) throws IOException {
        for (String url : getKeys(pageIndex)) {
            if ((int) pageIndex.get(url) == docId) {
                return url;
            }
        }
        return "";
    }

    private String getLastModificationDate(String url) {
        try {
            return new java.net.URL(url).openConnection().getLastModified() + "";
        } catch (Exception e) {
            return "Unknown";
        }
    }

    private int getPageSize(String url) {
        try {
            return new java.net.URL(url).openConnection().getContentLength();
        } catch (Exception e) {
            return 0;
        }
    }

    private String getTopKeywords(int docId, int limit) throws IOException {
        Map<String, Integer> frequencies = new HashMap<>();
        
        // Count frequencies for this document
        for (String term : getKeys(invertedIndexBody)) {
            List<Integer> docs = (List<Integer>) invertedIndexBody.get(term);
            int freq = Collections.frequency(docs, docId);
            if (freq > 0) {
                frequencies.put(term, freq);
            }
        }
        
        // Get top keywords
        return frequencies.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(limit)
                .map(e -> e.getKey() + "(" + e.getValue() + ")")
                .collect(Collectors.joining(", "));
    }

    private String getParentLinks(int docId, int limit) throws IOException {
        List<String> parents = new ArrayList<>();
        for (String key : getKeys(parentChildLinks)) {
            String[] parts = key.split("->");
            if (parts[1].equals(getUrlForDocId(docId))) {
                parents.add(parts[0]);
            }
        }
        return String.join(", ", parents.stream().limit(limit).collect(Collectors.toList()));
    }

    private String getChildLinks(int docId, int limit) throws IOException {
        List<String> children = new ArrayList<>();
        String docUrl = getUrlForDocId(docId);
        for (String key : getKeys(parentChildLinks)) {
            if (key.startsWith(docUrl + "->")) {
                children.add(key.split("->")[1]);
            }
        }
        return String.join(", ", children.stream().limit(limit).collect(Collectors.toList()));
    }

    private double calculateScore(int docId, String query) {
        // This score is already normalized by VectorSpaceModel
        return 1.0; // Placeholder - actual score is computed by VectorSpaceModel
    }

    @Override
    public void destroy() {
        try {
            if (recman != null) {
                recman.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
