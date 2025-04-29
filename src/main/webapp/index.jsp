<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>COMP4321 Search Engine</title>
    <style>
        body {
            font-family: Arial, sans-serif;
            margin: 0;
            padding: 20px;
            background: #f5f5f5;
        }
        .container {
            max-width: 800px;
            margin: 0 auto;
        }
        .search-box {
            text-align: center;
            margin: 50px 0;
        }
        .search-input {
            width: 80%;
            padding: 12px;
            font-size: 16px;
            border: 1px solid #ddd;
            border-radius: 4px;
            margin-bottom: 10px;
        }
        .search-button {
            padding: 12px 24px;
            font-size: 16px;
            background: #4285f4;
            color: white;
            border: none;
            border-radius: 4px;
            cursor: pointer;
        }
        .search-button:hover {
            background: #357abd;
        }
        .search-tips {
            color: #666;
            font-size: 14px;
            margin: 10px 0;
        }
        .results {
            margin-top: 30px;
        }
        .result-item {
            background: white;
            padding: 20px;
            margin-bottom: 20px;
            border-radius: 4px;
            box-shadow: 0 1px 3px rgba(0,0,0,0.1);
        }
        .result-title {
            color: #1a0dab;
            font-size: 18px;
            margin: 0 0 5px;
            text-decoration: none;
        }
        .result-title:hover {
            text-decoration: underline;
        }
        .result-url {
            color: #006621;
            font-size: 14px;
            margin-bottom: 5px;
        }
        .result-meta {
            color: #666;
            font-size: 13px;
            margin-bottom: 10px;
        }
        .result-keywords {
            color: #333;
            font-size: 13px;
            margin-bottom: 10px;
        }
        .result-links {
            font-size: 13px;
            color: #666;
        }
        .result-score {
            float: right;
            background: #f8f9fa;
            padding: 4px 8px;
            border-radius: 3px;
            color: #666;
            font-size: 13px;
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="search-box">
            <form action="search" method="GET">
                <input type="text" name="q" class="search-input" value="${param.q}" 
                       placeholder="Enter search terms or &quot;quoted phrases&quot;" required>
                <button type="submit" class="search-button">Search</button>
            </form>
            <div class="search-tips">
                Tips: Use quotes for exact phrases (e.g., "Hong Kong"). Up to 10 keywords per query.
            </div>
        </div>

        <div class="results">
            <c:if test="${not empty results}">
                <c:forEach var="result" items="${results}">
                    <div class="result-item">
                        <div class="result-score">${result.normalizedScore}/100</div>
                        <a href="${result.url}" class="result-title">${result.title}</a>
                        <div class="result-url">${result.url}</div>
                        <div class="result-meta">
                            Last modified: ${result.lastModified} | Size: ${result.size} bytes
                        </div>
                        <div class="result-keywords">
                            <strong>Top Keywords:</strong> ${result.keywords}
                        </div>
                        <div class="result-links">
                            <div><strong>Parent Links:</strong> ${result.parentLinks}</div>
                            <div><strong>Child Links:</strong> ${result.childLinks}</div>
                        </div>
                    </div>
                </c:forEach>
            </c:if>
            <c:if test="${empty results and not empty param.q}">
                <div class="result-item">No results found for your query.</div>
            </c:if>
        </div>
    </div>
</body>
</html>
