package com.kbrag.document.parser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.hpsf.Array;

/**
 * Heading-aware chunker: splits markdown-style text by headings (up to level 6),
 * then splits oversized bodies at paragraph boundaries with overlap.
 */
public class HeadingAwareChunker {
    private static final Pattern HEADING = Pattern.compile("(?m)^(#{1,6})\\s+(.+?)\\s*$");

    private final int maxSize;   // 800
    private final int minSize;   // 400
    private final int overlap;   // 100

    public HeadingAwareChunker(int maxSize, int minSize, int overlap) {
        this.maxSize = maxSize;
        this.minSize = minSize;
        this.overlap = overlap;
    }

    public List<Chunk> chunk(String text) {
        List<Section> sections = splitByHeadings(text);
        List<Chunk> result = new ArrayList<>();
        for(Section s : sections) {
            List<String> pieces = splitBody(s.body());
            for(int i = 0; i < pieces.size(); i++){
                String content = (i == 0 && !s.heading().isEmpty())
                        ? s.heading() + "\n" + pieces.get(i)
                        : pieces.get(i);
                result.add(new Chunk(content.trim(), s.heading(), result.size()));
            }
        }
        return mergeTinyTails(result);
    }

    // 访问权限 private，仅当前类可用
    // record 记录类
    // Section 类名
    // (String heading, String body) 叫作「组件列表」，两个成员字段
    private record Section(String heading, String body) {}

    private List<Section> splitByHeadings(String text) {
        List<Section> sections = new ArrayList<>();
        Matcher m = HEADING.matcher(text);
        int lastEnd = 0;
        String lastHeading = "";
        while (m.find()) {
            sections.add(new Section(lastHeading, text.substring(lastEnd, m.start()).trim()));
            lastHeading = m.group(2).trim();
            lastEnd = m.end();
        }
        sections.add(new Section(lastHeading, text.substring(lastEnd).trim()));
        return sections;
    }

    /** Split a body into <= maxSize pieces at paragraph boundaries, carrying overlap. */
    private List<String> splitBody(String body) {
        List<String> paragraphs = Arrays.stream(body.split("[\\r\\n]+"))
                .map(String::trim).filter(p -> !p.isEmpty()).toList();
        List<String> pieces = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for(String p : paragraphs) {
            if (buf.length() > 0 && buf.length() + p.length() + 1 > maxSize) {
                pieces.add(buf.toString());
                buf = new StringBuilder(tail(buf.toString()));
            }
            if (p.length() > maxSize) {
                // single oversized paragraph: hard split with overlap
                if (buf.length() > 0) pieces.add(buf.toString());  // buf 可能为空：跳过空块，否则 TDD 测试 2 的 chunks.get(0) 是空串
                buf = new StringBuilder();
                String rest = p;
                while (rest.length() > maxSize) {
                    pieces.add(rest.substring(0, maxSize));
                    rest = rest.substring(maxSize - overlap);
                }
                buf.append(rest);
            } else {
                buf.append(p).append('\n');
            }
        }
        if(!buf.isEmpty()) pieces.add(buf.toString());
        return pieces;
    }

    /** Last `overlap` chars of previous piece, used as the head of the next piece. */
    private String tail(String s) {
        return s.length() <= overlap ? s : s.substring(s.length() - overlap);
    }

    /** Merge a tiny last chunk (below minSize) into the previous one. */
    private List<Chunk> mergeTinyTails(List<Chunk> chunks) {
        if (chunks.size() < 2) return chunks;
        List<Chunk> out = new ArrayList<>(chunks);
        Chunk last = out.remove(out.size() - 1);
        Chunk prev = out.remove(out.size() - 1);
        if (last.content().length() < minSize) {
            out.add(new Chunk(prev.content() + "\n" + last.content(),
                    prev.headingPath(), prev.index()));
        } else {
            out.add(prev);
            out.add(last);
        }
        return out;
    }

    
}
