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

public class Spider {
    private RecordManager recman;
    private final HTree pageIndex;
    private final HTree invertedIndexBody;
    private final HTree invertedIndexTitle;
    private final HTree parentChildLinks;
    private int currentPageID = 0;

    Spider(String dbName) throws IOException {
        recman = RecordManagerFactory.createRecordManager(dbName);
        pageIndex = HTree.createInstance(recman);
        invertedIndexBody = HTree.createInstance(recman);
        invertedIndexTitle = HTree.createInstance(recman);
        parentChildLinks = HTree.createInstance(recman);
    }

    // Fetch pages using BFS
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

            List<String> links = extractLinks(url, pageContent);
            for (String link : links) {
                if (!visited.contains(link)) {
                    visited.add(link);
                    queue.add(link);
                }
                parentChildLinks.put(pageID + "->" + link, true);
            }

            indexPage(pageID, pageContent);
        }

        recman.commit();
    }

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

    private void indexPage(int pageID, String content) throws ParserException, IOException {

        String title = extractTitle(content);
        if (title != null && !title.isEmpty()) {
            invertedIndexTitle.put(pageID, title);
        }

        Parser parser = new Parser();
        parser.setInputHTML(content);

        StringBean stringBean = new StringBean();
        stringBean.setLinks(false);
        parser.visitAllNodesWith(stringBean);

        String body = stringBean.getStrings();

        if (body != null && !body.isEmpty()) {
            indexText(pageID, body, invertedIndexBody);
        }
    }


    private String extractTitle(String content) throws ParserException {
        Parser parser = new Parser(content);
        NodeList nodeList = parser.extractAllNodesThatMatch(new TagNameFilter("title"));
        if (nodeList != null && nodeList.size() > 0) {
            return nodeList.elementAt(0).toPlainTextString();
        }
        return "";
    }

    private void indexText(int pageID, String text, HTree invertedIndexBody) throws IOException {
        if (text == null || text.isEmpty()) {
            return;
        }

        String[] words = text.toLowerCase().split("\\W+");
        Set<String> stopWords = loadStopWords("src/stopwords.txt");
        Porter stemmer = new Porter();

        for (String word : words) {
            if (stopWords.contains(word)) continue;

            String stem = stemmer.stripAffixes(word);
            if (stem.isEmpty()) continue;

            List<Integer> pageIDs = (List<Integer>) invertedIndexBody.get(stem);
            if (pageIDs == null) {
                pageIDs = new ArrayList<>();
            }
            if (!pageIDs.contains(pageID)) {
                pageIDs.add(pageID);
            }
            invertedIndexBody.put(stem, pageIDs);
        }
    }

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

    private String getTitleFromIndex(int pageID) throws IOException {
        String title = (String) invertedIndexTitle.get(pageID);
        return (title != null) ? title : "Untitled";
    }

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
                .limit(20) // Limit to top 10 keywords
                .map(entry -> entry.getKey() + " " + entry.getValue())
                .reduce((a, b) -> a + "; " + b)
                .orElse("");
    }

    private String getLastModificationDate(String url) {
        try {
            URLConnection connection = new URL(url).openConnection();
            long lastModified = connection.getLastModified();
            return (lastModified == 0) ? "Unknown" : new Date(lastModified).toString();
        } catch (Exception e) {
            return "Unknown";
        }
    }

    private int getPageSize(String url) {
        try {
            URLConnection connection = new URL(url).openConnection();
            int contentLength = connection.getContentLength();
            return (contentLength == -1) ? 0 : contentLength;
        } catch (Exception e) {
            return 0;
        }
    }

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

    // Close the database connection
    public void close() throws IOException {
        recman.close();
    }

    public static void main(String[] args) {
        try {
            Spider spider = new Spider("spider_db");
            spider.crawl("https://comp4321-hkust.github.io/testpages/testpage.htm", 30);
            spider.generateSpiderResult("spider_result.txt");
            spider.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}