package com.kbrag.document.parser;

import java.io.IOException;
import java.io.InputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

public class PdfParser implements DocumentParser {
    @Override
    public String parse(InputStream in, String filename) throws IOException{
        // 包成 RandomAccessRead(流式,不整读;PDFBox 3 的正规做法)
        try(PDDocument doc = Loader.loadPDF(new RandomAccessReadBuffer(in))) {
            return new PDFTextStripper().getText(doc);
        }
    }
    
}
