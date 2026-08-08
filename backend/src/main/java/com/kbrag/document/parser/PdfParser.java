package com.kbrag.document.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import java.io.IOException;
import java.io.InputStream;

public class PdfParser implements DocumentParser {
    @Override
    public String parse(InputStream in, String filename) throws IOException{
        // PDFBox 3.x 移除了 PDDocument.load(InputStream),统一走 Loader.loadPDF
        try(PDDocument doc = Loader.loadPDF(in)) {
            return new PDFTextStripper().getText(doc);
        }
    }
    
}
