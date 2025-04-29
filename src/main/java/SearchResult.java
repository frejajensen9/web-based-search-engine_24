import java.util.List;

public class SearchResult implements Comparable<SearchResult> {
    private String url;
    private String title;
    private String lastModified;
    private int size;
    private String keywords;
    private String parentLinks;
    private String childLinks;
    private double score;
    private int normalizedScore;

    public SearchResult(String url, String title, String lastModified, int size, 
                       String keywords, String parentLinks, String childLinks, double score) {
        this.url = url;
        this.title = title;
        this.lastModified = lastModified;
        this.size = size;
        this.keywords = keywords;
        this.parentLinks = parentLinks;
        this.childLinks = childLinks;
        this.score = score;
        this.normalizedScore = (int) Math.round(score * 100); // Convert to 0-100 scale
    }

    // Getters
    public String getUrl() { return url; }
    public String getTitle() { return title; }
    public String getLastModified() { return lastModified; }
    public int getSize() { return size; }
    public String getKeywords() { return keywords; }
    public String getParentLinks() { return parentLinks; }
    public String getChildLinks() { return childLinks; }
    public double getScore() { return score; }
    public int getNormalizedScore() { return normalizedScore; }

    // For sorting results by score in descending order
    @Override
    public int compareTo(SearchResult other) {
        return Double.compare(other.score, this.score);
    }
}
