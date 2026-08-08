package com.kbrag.document.parser;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import java.io.IOException;
import java.io.InputStream;
import java.util.stream.Collectors;

public class WordParser implements DocumentParser {
    @Override
    public String parse(InputStream in, String filename) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(in)) {
            return doc.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .collect(Collectors.joining("\n"));
        }
    }
}
