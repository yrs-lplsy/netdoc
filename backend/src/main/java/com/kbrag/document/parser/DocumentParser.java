package com.kbrag.document.parser;

import java.io.IOException;
import java.io.InputStream;

public interface DocumentParser {
    String parse(InputStream in, String filename) throws IOException;
}