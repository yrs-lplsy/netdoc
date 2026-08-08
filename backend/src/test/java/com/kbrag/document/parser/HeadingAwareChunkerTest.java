package com.kbrag.document.parser;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HeadingAwareChunkerTest {
    private final HeadingAwareChunker chunker =
            new HeadingAwareChunker(800, 400, 100);

    @Test
    void heading_is_prepended_and_boundary_respected() {
        String text = "# 第一章 安装\n\n设备安装步骤说明。\n\n"
                + "## 1.1 接线\n\n" + "A".repeat(600) + "。\n\n"
                + "## 1.2 上电\n\n" + "B".repeat(600) + "。";
        List<Chunk> chunks = chunker.chunk(text);
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.get(0).headingPath().contains("第一章"));
        assertTrue(chunks.stream().anyMatch(c -> c.headingPath().contains("1.2")));
        // 每个 chunk 不超过 maxSize + overlap
        for (Chunk c : chunks) {
            System.out.println(c.content());
            assertTrue(c.content().length() <= 800 + 100);
        }
    }

    @Test
    void long_body_is_split_with_overlap() {
        String body = "A".repeat(3000);
        List<Chunk> chunks = chunker.chunk(body);
        assertTrue(chunks.size() >= 3);
        assertTrue(chunks.get(0).content().endsWith("A".repeat(100))); // overlap 尾部
        assertTrue(chunks.get(1).content().startsWith("A".repeat(100)));
    }

    @Test
    void empty_input_returns_empty() {
        assertTrue(chunker.chunk("").isEmpty());
    }
    
}
