package com.kbrag.document.parser;

import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class MarkdownParser implements DocumentParser {
    private final Parser parser = Parser.builder().build();
    @Override
    public String parse(InputStream in, String filename) throws IOException {
        String md = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        Node node = parser.parse(md);
        // commonmark 不直接输出纯文本：简易转纯文本（strip 链接语法即可，标题保留 # 前缀供分块器使用）
        return md;
    }
}
