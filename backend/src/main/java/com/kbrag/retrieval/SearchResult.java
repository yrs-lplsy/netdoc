package com.kbrag.retrieval;

public record SearchResult(long chunkId, long docId, String content, String headingPath) {}
