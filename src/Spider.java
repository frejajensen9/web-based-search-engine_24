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
    // database manager that handles all the storage stuff
    private RecordManager recman;

    // maps URLs to page IDs
    private final HTree pageIndex;

    // stores which words appear in which pages (for the body text)
    private final HTree invertedIndexBody;

    // stores which words appear in which pages (for the titles)
    private final HTree invertedIndexTitle;

    // keeps track of parent-child relationships between pages (like a family tree for links)
    private final HTree parentChildLinks;

    // counter to give each page a unique ID
    private int currentPageID = 0;

    // Constructor: sets up the database and all the maps
    Spider(String dbName) throws IOException {
        // create or open the database
        recman = RecordManagerFactory.createRecordManager(dbName);

        // set up the maps for URLs, words, and links
        pageIndex = HTree.createInstance(recman);
        invertedIndexBody = HTree.createInstance(recman);
        invertedIndexTitle = HTree.createInstance(recman);
        parentChildLinks = HTree.createInstance(recman);
    }

    // main crawling method, starts from a URL and crawls up to maxPages
    public void crawl(String startURL, int maxPages) throws IOException, ParserException {
        // A queue to keep track of URLs to visit (BFS style)
        Queue<String> queue = new LinkedList<>();
        // A set to keep track of URLs we've already seen
        Set<String> visited = new HashSet<>();

        // start with the first URL
        queue.add(startURL);
        visited.add(startURL);

        // Keep crawling until we run out of URLs or hit the max page limit
        while (!queue.isEmpty() && currentPageID < maxPages) {
            // grab the next URL from the queue
            String url = queue.poll();

            // fetch the page content
            String pageContent = fetchPage(url);
            if (pageContent == null) continue; // Skip if we can't fetch the page

            // give this page a unique ID and store it in the pageIndex
            int pageID = currentPageID++;
            pageIndex.put(url, pageID);

            // extract all the links from this page
            List<String> links = extractLinks(url, pageContent);
            for (String link : links) {
                // If we haven't seen this link before, add it to the queue
                if (!visited.contains(link)) {
                    visited.add(link);
                    queue.add(link);
                }
                // Record the parent-child relationship (this page -> child link)
                parentChildLinks.put(pageID + "->" + link, true);
            }

            // Index the page (extract title, body, and build the word maps)
            indexPage(pageID, pageContent);
        }

        // Save all the changes to the database
        recman.commit();
    }

    // fetches the content of a web page
    private String fetchPage(String url) {
        try {
            // Open a connection to the URL
            URLConnection connection = new URL(url).openConnection();
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder content = new StringBuilder();
            String line;

            // Read the page line by line
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
            reader.close();

            // Return the page content as a big string
            return content.toString();
        } catch (Exception e) {
            // skip if something is wrong
            System.err.println("Error fetching URL: " + url);
            return null;
        }
    }

    // Extracts all the links from a page
    private List<String> extractLinks(String baseURL, String content) throws ParserException, MalformedURLException {
        List<String> links = new ArrayList<>();

        // Use the HTML parser to find all the <a> tags
        Parser parser = new Parser(content);
        NodeList nodeList = parser.extractAllNodesThatMatch(new NodeClassFilter(LinkTag.class));

        // Go through each link tag
        for (int i = 0; i < nodeList.size(); i++) {
            LinkTag linkTag = (LinkTag) nodeList.elementAt(i);
            String link = linkTag.getLink();

            // If the link is relative (like "/about"), make it absolute
            if (!link.startsWith("http")) {
                link = new URL(new URL(baseURL), link).toString();
            }

            // only keep HTTP/HTTPS links
            if (link.startsWith("http")) {
                links.add(link);
            }
        }

        return links;
    }

    // Indexes a page by extracting the title and body, then building the word maps
    private void indexPage(int pageID, String content) throws ParserException, IOException {
        // Extract the title of the page
        String title = extractTitle(content);
        if (title != null && !title.isEmpty()) {
            // Store the title in the title index
            invertedIndexTitle.put(pageID, title);
        }

        // Use the HTML parser to extract the body text (ignoring links)
        Parser parser = new Parser();
        parser.setInputHTML(content);

        StringBean stringBean = new StringBean();
        stringBean.setLinks(false); // don't include links in the text
        parser.visitAllNodesWith(stringBean);

        String body = stringBean.getStrings();

        // if there's body text, index it (tokenize, remove stop words, and stem)
        if (body != null && !body.isEmpty()) {
            indexText(pageID, body, invertedIndexBody);
        }
    }

    // Extracts the title of a page
    private String extractTitle(String content) throws ParserException {
        // Use the HTML parser to find the <title> tag
        Parser parser = new Parser(content);
        NodeList nodeList = parser.extractAllNodesThatMatch(new TagNameFilter("title"));
        if (nodeList != null && nodeList.size() > 0) {
            // Return the text inside the <title> tag
            return nodeList.elementAt(0).toPlainTextString();
        }
        return ""; // If there's no title, return an empty string
    }

    // Indexes the text of a page (tokenizes, removes stop words, and stems)
    private void indexText(int pageID, String text, HTree invertedIndexBody) throws IOException {
        if (text == null || text.isEmpty()) {
            return; // Skip if there's no text
        }

        // split the text into words (using non-word characters as separators)
        String[] words = text.toLowerCase().split("\\W+");

        // load the list of stop words
        Set<String> stopWords = loadStopWords("src/stopwords.txt");

        // Initialize the Porter stemmer (to reduce words to their root form)
        Porter stemmer = new Porter();

        // Go through each word
        for (String word : words) {
            if (stopWords.contains(word)) continue; // Skip stop words

            // stem the words
            String stem = stemmer.stripAffixes(word);
            if (stem.isEmpty()) continue; // Skip empty stems

            // Get the list of pages where this word appears
            List<Integer> pageIDs = (List<Integer>) invertedIndexBody.get(stem);
            if (pageIDs == null) {
                pageIDs = new ArrayList<>();
            }

            // Add this page to the list if it's not there yet
            if (!pageIDs.contains(pageID)) {
                pageIDs.add(pageID);
            }

            // Update the word map with the new list of pages
            invertedIndexBody.put(stem, pageIDs);
        }
    }

    // Loads the list of stop words from a file
    private Set<String> loadStopWords(String filename) {
        Set<String> stopWords = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
            String line;
            // Read each line and add it to the set of stop words
            while ((line = reader.readLine()) != null) {
                stopWords.add(line.trim());
            }
        } catch (IOException e) {
            // error if file cant be read
            System.err.println("Error loading stop words: " + e.getMessage());
        }
        return stopWords;
    }

    // generates the spider result file (spider_result.txt)
    public void generateSpiderResult(String outputFile) throws IOException {
        BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile));

        // Go through all the URLs in the page index
        FastIterator iterator = pageIndex.keys();
        String url;
        while ((url = (String) iterator.next()) != null) {
            int pageID = (int) pageIndex.get(url);

            // Get the title of the page
            String title = getTitleFromIndex(pageID);

            // Get the last modification date and size of the page
            String lastModDate = getLastModificationDate(url);
            int size = getPageSize(url);

            // Get the top keywords and their frequencies for this page
            String keywordFrequencies = getKeywordsFromIndex(pageID);

            // Get the child links for this page
            List<String> childLinks = getChildLinks(pageID);

            // Write all this info to the output file
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

    // Gets the title of a page from the title index
    private String getTitleFromIndex(int pageID) throws IOException {
        String title = (String) invertedIndexTitle.get(pageID);
        return (title != null) ? title : "Untitled"; // return "Untitled" if there's no title
    }

    // Gets the top keywords and their frequencies for a page
    private String getKeywordsFromIndex(int pageID) throws IOException {
        Map<String, Integer> keywordFreq = new HashMap<>();

        // Go through all the words in the inverted index
        FastIterator iterator = invertedIndexBody.keys();
        String key;
        while ((key = (String) iterator.next()) != null) {
            // Get the list of pages where this word appears
            List<Integer> pageIDs = (List<Integer>) invertedIndexBody.get(key);
            if (pageIDs != null && pageIDs.contains(pageID)) {
                // Count how many times this word appears in the page
                keywordFreq.put(key, Collections.frequency(pageIDs, pageID));
            }
        }

        // Sort the keywords by frequency and return the top 20
        return keywordFreq.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(20) // Only keep the top 20 keywords
                .map(entry -> entry.getKey() + " " + entry.getValue())
                .reduce((a, b) -> a + "; " + b)
                .orElse(""); // If there are no keywords, return an empty string
    }

    // Gets the last modification date of a page
    private String getLastModificationDate(String url) {
        try {
            URLConnection connection = new URL(url).openConnection();
            long lastModified = connection.getLastModified();
            return (lastModified == 0) ? "Unknown" : new Date(lastModified).toString();
        } catch (Exception e) {
            return "Unknown"; // If we can't get the date, return "Unknown"
        }
    }

    // Gets the size of a page
    private int getPageSize(String url) {
        try {
            URLConnection connection = new URL(url).openConnection();
            int contentLength = connection.getContentLength();
            return (contentLength == -1) ? 0 : contentLength; // If the size is unknown, return 0
        } catch (Exception e) {
            return 0; // If something goes wrong, return 0
        }
    }

    // Gets the child links of a page
    private List<String> getChildLinks(int pageID) throws IOException {
        List<String> childLinks = new ArrayList<>();

        // Go through all the parent-child relationships
        FastIterator iterator = parentChildLinks.keys();
        String key;
        while ((key = (String) iterator.next()) != null) {
            // If this relationship involves the current page as the parent, add the child link
            if (key.startsWith(pageID + "->")) {
                String childURL = key.split("->")[1];
                childLinks.add(childURL);
            }
        }

        return childLinks;
    }

    // close database connection
    public void close() throws IOException {
        recman.close();
    }

    public static void main(String[] args) {
        try {
            // Create a new Spider instance and start crawling
            Spider spider = new Spider("spider_db");
            spider.crawl("https://comp4321-hkust.github.io/testpages/testpage.htm", 30);
            spider.generateSpiderResult("spider_result.txt");
            spider.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}