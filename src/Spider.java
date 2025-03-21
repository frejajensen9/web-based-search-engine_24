import jdbm.RecordManager;
import jdbm.RecordManagerFactory;
import jdbm.helper.FastIterator;
import jdbm.htree.HTree;
import org.htmlparser.*;
import org.htmlparser.beans.StringBean;
import org.htmlparser.filters.NodeClassFilter;
import org.htmlparser.filters.TagNameFilter;
import org.htmlparser.tags.LinkTag;
import org.htmlparser.util.NodeList;
import org.htmlparser.util.ParserException;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.*;

/**
 * Spider class that crawls web pages using BFS, indexes them into JDBM HTrees,
 * and now supports phrase searching over the page body.
 */
public class Spider {
    private RecordManager recman;
    private final HTree pageIndex;                 // Maps URL (String) -> pageID (Integer)
    private final HTree invertedIndexBody;         // Inverted index: stemmed word -> List<Integer> (page IDs)
    private final HTree invertedIndexTitle;        // Maps pageID (Integer) -> title (String)
    private final HTree parentChildLinks;          // Maps "pageID->childURL" -> Boolean (true)

    // New inverted index for phrase search: stemmed word -> Map<pageID, List<Integer>>
    // This stores the positions (token index) at which the word occurs in each page.
    private final HTree invertedIndexBodyPositions;

    private int currentPageID = 0;

    /**
     * Constructor: creates a RecordManager and initializes the HTrees.
     */
    public Spider(String dbName) throws IOException {
        recman = RecordManagerFactory.createRecordManager(dbName);
        pageIndex = HTree.createInstance(recman);
        invertedIndexBody = HTree.createInstance(recman);
        invertedIndexTitle = HTree.createInstance(recman);
        parentChildLinks = HTree.createInstance(recman);
        invertedIndexBodyPositions = HTree.createInstance(recman); // initialize the positions index
    }

    /**
     * Crawls pages starting from the given URL using BFS up to maxPages.
     */
    public void crawl(String startURL, int maxPages) throws IOException, ParserException {
        Queue<String> queue = new LinkedList<>();
        Set<String> visited = new HashSet<>();
        queue.add(startURL);
        visited.add(startURL);

        while (!queue.isEmpty() && currentPageID < maxPages) {
            String url = queue.poll();

            String pageContent = fetchPage(url);
            if (pageContent == null) continue;

            int pageID = currentPageID++;
            pageIndex.put(url, pageID);

            // Extract links and record parent-child relationships
            List<String> links = extractLinks(url, pageContent);
            for (String link : links) {
                if (!visited.contains(link)) {
                    visited.add(link);
                    queue.add(link);
                }
                parentChildLinks.put(pageID + "->" + link, true);
            }

            // Index the page content (both title and body)
            indexPage(pageID, pageContent);
        }

        recman.commit();
    }

    /**
     * Fetches the content of the page at the given URL.
     */
    private String fetchPage(String url) {
        try {
            URLConnection connection = new URL(url).openConnection();
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
            reader.close();
            return content.toString();
        } catch (Exception e) {
            System.err.println("Error fetching URL: " + url);
            return null;
        }
    }

    /**
     * Extracts all valid HTTP links from the page content using the HTMLParser library.
     */
    private List<String> extractLinks(String baseURL, String content) throws ParserException, MalformedURLException {
        List<String> links = new ArrayList<>();
        Parser parser = new Parser(content);
        NodeList nodeList = parser.extractAllNodesThatMatch(new NodeClassFilter(LinkTag.class));

        for (int i = 0; i < nodeList.size(); i++) {
            LinkTag linkTag = (LinkTag) nodeList.elementAt(i);
            String link = linkTag.getLink();

            if (!link.startsWith("http")) {
                link = new URL(new URL(baseURL), link).toString();
            }
            if (link.startsWith("http")) {
                links.add(link);
            }
        }
        return links;
    }

    /**
     * Indexes the page by extracting the title and body text.
     */
    private void indexPage(int pageID, String content) throws ParserException, IOException {
        // Extract and index the title
        String title = extractTitle(content);
        if (title != null && !title.isEmpty()) {
            invertedIndexTitle.put(pageID, title);
        }

        // Extract the body text using StringBean (ignores links)
        Parser parser = new Parser();
        parser.setInputHTML(content);
        StringBean stringBean = new StringBean();
        stringBean.setLinks(false);
        parser.visitAllNodesWith(stringBean);
        String body = stringBean.getStrings();

        // If body text is present, index it (both for simple word search and phrase search)
        if (body != null && !body.isEmpty()) {
            indexText(pageID, body, invertedIndexBody);
        }
    }

    /**
     * Extracts the title from the page content by searching for the <title> tag.
     */
    private String extractTitle(String content) throws ParserException {
        Parser parser = new Parser(content);
        NodeList nodeList = parser.extractAllNodesThatMatch(new TagNameFilter("title"));
        if (nodeList != null && nodeList.size() > 0) {
            return nodeList.elementAt(0).toPlainTextString();
        }
        return "";
    }

    /**
     * Tokenizes the text, filters stop words, applies stemming (using Porter stemmer),
     * and indexes each word.
     *
     * Additionally, stores the positions (token index) for each word into invertedIndexBodyPositions.
     */
    private void indexText(int pageID, String text, HTree invertedIndexBody) throws IOException {
        if (text == null || text.isEmpty()) {
            return;
        }

        // Split text into words by non-word characters and convert to lowercase
        String[] words = text.toLowerCase().split("\\W+");
        Set<String> stopWords = loadStopWords("src/stopwords.txt");
        Porter stemmer = new Porter();
        int pos = 0; // Position counter for the token within the text

        for (String word : words) {
            if (word.isEmpty()) {
                pos++;
                continue;
            }
            // Skip stop words
            if (stopWords.contains(word)) {
                pos++;
                continue;
            }

            // Stem the word using the Porter stemmer
            String stem = stemmer.stripAffixes(word);
            if (stem.isEmpty()) {
                pos++;
                continue;
            }

            // ----------- Update the simple inverted index (word -> list of page IDs) -----------
            List<Integer> pageIDs = (List<Integer>) invertedIndexBody.get(stem);
            if (pageIDs == null) {
                pageIDs = new ArrayList<>();
            }
            if (!pageIDs.contains(pageID)) {
                pageIDs.add(pageID);
            }
            invertedIndexBody.put(stem, pageIDs);

            // ----------- Update the positions inverted index (word -> (pageID -> list of positions)) -----------
            Map<Integer, List<Integer>> posMap = (Map<Integer, List<Integer>>) invertedIndexBodyPositions.get(stem);
            if (posMap == null) {
                posMap = new HashMap<>();
            }
            List<Integer> positions = posMap.get(pageID);
            if (positions == null) {
                positions = new ArrayList<>();
            }
            positions.add(pos); // record the current token position
            posMap.put(pageID, positions);
            invertedIndexBodyPositions.put(stem, posMap);

            pos++;
        }
    }

    /**
     * Loads stop words from a file into a Set.
     */
    private Set<String> loadStopWords(String filename) {
        Set<String> stopWords = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stopWords.add(line.trim());
            }
        } catch (IOException e) {
            System.err.println("Error loading stop words: " + e.getMessage());
        }
        return stopWords;
    }

    /**
     * Generates a result file summarizing the crawled pages.
     */
    public void generateSpiderResult(String outputFile) throws IOException {
        BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile));
        FastIterator iterator = pageIndex.keys();

        String url;
        while ((url = (String) iterator.next()) != null) {
            int pageID = (int) pageIndex.get(url);
            String title = getTitleFromIndex(pageID);
            String lastModDate = getLastModificationDate(url);
            int size = getPageSize(url);
            String keywordFrequencies = getKeywordsFromIndex(pageID);
            List<String> childLinks = getChildLinks(pageID);

            writer.write(title + "\n");
            writer.write(url + "\n");
            writer.write(lastModDate + ", " + size + " bytes\n");
            writer.write("Keywords: " + keywordFrequencies + "\n");
            writer.write("Child Links:\n");
            for (String child : childLinks) {
                writer.write(child + "\n");
            }
            writer.write("-----------------------------------------\n");
        }
        writer.close();
    }

    /**
     * Retrieves the title from the title index.
     */
    private String getTitleFromIndex(int pageID) throws IOException {
        String title = (String) invertedIndexTitle.get(pageID);
        return (title != null) ? title : "Untitled";
    }

    /**
     * Retrieves the top keywords (with frequency) for a page from the body inverted index.
     */
    private String getKeywordsFromIndex(int pageID) throws IOException {
        Map<String, Integer> keywordFreq = new HashMap<>();
        FastIterator iterator = invertedIndexBody.keys();

        String key;
        while ((key = (String) iterator.next()) != null) {
            List<Integer> pageIDs = (List<Integer>) invertedIndexBody.get(key);
            if (pageIDs != null && pageIDs.contains(pageID)) {
                keywordFreq.put(key, Collections.frequency(pageIDs, pageID));
            }
        }

        return keywordFreq.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(20)
                .map(entry -> entry.getKey() + " " + entry.getValue())
                .reduce((a, b) -> a + "; " + b)
                .orElse("");
    }

    /**
     * Retrieves the last modification date of a page.
     */
    private String getLastModificationDate(String url) {
        try {
            URLConnection connection = new URL(url).openConnection();
            long lastModified = connection.getLastModified();
            return (lastModified == 0) ? "Unknown" : new Date(lastModified).toString();
        } catch (Exception e) {
            return "Unknown";
        }
    }

    /**
     * Retrieves the page size (in bytes) from the URL connection.
     */
    private int getPageSize(String url) {
        try {
            URLConnection connection = new URL(url).openConnection();
            int contentLength = connection.getContentLength();
            return (contentLength == -1) ? 0 : contentLength;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Retrieves all child links (URLs) for a given page from the parentChildLinks index.
     */
    private List<String> getChildLinks(int pageID) throws IOException {
        List<String> childLinks = new ArrayList<>();
        FastIterator iterator = parentChildLinks.keys();

        String key;
        while ((key = (String) iterator.next()) != null) {
            if (key.startsWith(pageID + "->")) {
                String childURL = key.split("->")[1];
                childLinks.add(childURL);
            }
        }
        return childLinks;
    }

    /**
     * Searches for a given phrase in the page bodies.
     * The phrase is tokenized, normalized, and stemmed.
     * Then the method uses the positions index to verify if the words occur consecutively.
     *
     * @param phrase The phrase to search for.
     * @return A list of page IDs where the phrase appears.
     */
    public List<Integer> searchPhrase(String phrase) throws IOException {
        // Normalize and tokenize the phrase
        String[] words = phrase.toLowerCase().split("\\W+");
        Porter stemmer = new Porter();
        List<String> stemmedWords = new ArrayList<>();
        for (String word : words) {
            if (!word.isEmpty()) {
                stemmedWords.add(stemmer.stripAffixes(word));
            }
        }
        if (stemmedWords.isEmpty()) return new ArrayList<>();

        // Retrieve postings for the first word from the positions index.
        Map<Integer, List<Integer>> candidatePostings = (Map<Integer, List<Integer>>) invertedIndexBodyPositions.get(stemmedWords.get(0));
        if (candidatePostings == null) return new ArrayList<>();

        // For each subsequent word, update candidate pages by verifying that for each candidate position p,
        // the next word appears at position p+1, then p+2, etc.
        for (int offset = 1; offset < stemmedWords.size(); offset++) {
            Map<Integer, List<Integer>> postings = (Map<Integer, List<Integer>>) invertedIndexBodyPositions.get(stemmedWords.get(offset));
            if (postings == null) return new ArrayList<>();
            Map<Integer, List<Integer>> newCandidatePostings = new HashMap<>();

            // Check each candidate page
            for (Integer pageID : candidatePostings.keySet()) {
                if (postings.containsKey(pageID)) {
                    List<Integer> positionsList = candidatePostings.get(pageID);
                    List<Integer> nextPositions = postings.get(pageID);
                    List<Integer> newPositions = new ArrayList<>();
                    // For each candidate starting position, check if the word at the required offset exists
                    for (Integer pos : positionsList) {
                        if (nextPositions.contains(pos + 1)) { // offset of 1 for the next word
                            newPositions.add(pos);
                        }
                    }
                    if (!newPositions.isEmpty()) {
                        newCandidatePostings.put(pageID, newPositions);
                    }
                }
            }
            candidatePostings = newCandidatePostings;
            if (candidatePostings.isEmpty()) return new ArrayList<>();
        }
        // Return the list of page IDs where the entire phrase was found.
        return new ArrayList<>(candidatePostings.keySet());
    }

    /**
     * Closes the database connection.
     */
    public void close() throws IOException {
        recman.close();
    }

    /**
     * Main method: creates an instance of Spider, crawls pages, generates the result file,
     * and demonstrates a sample phrase search.
     */
    public static void main(String[] args) {
        try {
            Spider spider = new Spider("spider_db");
            // Crawl starting from a given URL and limit to 30 pages.
            spider.crawl("https://comp4321-hkust.github.io/testpages/testpage.htm", 30);
            spider.generateSpiderResult("spider_result.txt");

            // Demonstrate phrase search:
            // For example, search for the phrase "data structures" (adjust as needed)
            List<Integer> results = spider.searchPhrase("data structures");
            System.out.println("Pages containing the phrase 'data structures': " + results);

            spider.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
