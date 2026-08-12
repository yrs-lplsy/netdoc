package com.kbrag.document;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.annotation.JsonAppend.Attr;
import com.kbrag.ai.EmbeddingService;
import com.kbrag.ai.Tokenizer;
import com.kbrag.cache.ChatCacheService;
import com.kbrag.document.parser.Chunk;
import com.kbrag.document.parser.DocumentParser;
import com.kbrag.document.parser.HeadingAwareChunker;
import com.kbrag.document.parser.MarkdownParser;
import com.kbrag.document.parser.PdfParser;
import com.kbrag.document.parser.WordParser;
import com.kbrag.kg.KgService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class DocumentService {
    @Autowired DocumentRepository documents;
    @Autowired DocumentChunkRepository chunks;
    @Autowired @Lazy DocumentService self;  // 自注入代理：同类内部调用不走 Spring 代理，@Async 会退化成同步

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private Tokenizer tokenizer;

    @Autowired
    private KgService kgService;

    @Autowired
    private ChatCacheService chatCache;

    private final HeadingAwareChunker chunker;
    private static final int MAX_SIZE = 800, MIN_SIZE = 400, OVERLAP = 100;

    public DocumentService() {
        this.chunker = new HeadingAwareChunker(MAX_SIZE, MIN_SIZE, OVERLAP);
    }

    public Document upload(MultipartFile file, Long kbId) throws IOException {
        Document doc = new Document();
        doc.setTitle(file.getOriginalFilename());
        doc.setUploader(0L);
        doc.setKbId(kbId);
        documents.save(doc);
        chatCache.invalidateKb(kbId);              // 新文档使旧缓存失效
        // MultipartFile 背后是 Tomcat 临时文件，请求结束后会被删除；
        // 必须先在请求线程内把内容读成字节数组，再交给异步线程处理
        byte[] bytes = file.getBytes();
        self.processAsync(doc.getId(), doc.getKbId(), bytes, file.getOriginalFilename());   // 必须经代理调用，@Async 才生效
        return doc;
    }

    @Async
    public void processAsync(Long docId, Long kbId, byte[] content, String filename) {
        Document doc = documents.findById(docId).orElseThrow();
        try {
            String text = pickParser(filename).parse(new ByteArrayInputStream(content), filename);
            List<Chunk> parsed = chunker.chunk(text);
            List<DocumentChunk> entities = parsed.stream().map(c -> {
                DocumentChunk e = new DocumentChunk();
                e.setDocId(docId);
                e.setKbId(kbId);
                e.setChunkIndex(c.index());
                e.setContent(c.content());
                e.setHeadingPath(c.headingPath());
                e.setTokenCount(c.content().length()); // 中英混排近似，Phase 3 换真实 token 统计
                return e;
            }).toList();
            // 向量化 + 关键词分词
            List<String> contents = parsed.stream().map(Chunk::content).toList();
            List<float[]> vectors = embeddingService.embed(contents);
            for (int i = 0; i < entities.size(); i++) {
                entities.get(i).setEmbedding(vectors.get(i));   // float[] 直接赋值(hibernate-vector 映射)
                entities.get(i).setSegmentedText(tokenizer.segment(entities.get(i).getContent()));
            }
            chunks.saveAll(entities);
            try {
                kgService.extractAndSave(kbId, docId,
                        parsed.stream().map(Chunk::content).toList());
            } catch (Exception ex) {
                log.warn("KG extraction failed for doc {}: {}", docId, ex.getMessage());
            }

            doc.setStatus(DocumentStatus.READY);
        } catch (Exception ex) {
            doc.setStatus(DocumentStatus.FAILED);
            doc.setErrorMessage(ex.getMessage());
        }
        doc.setUpdatedAt(LocalDateTime.now());     // 版本戳数据源(Step 1)
        chatCache.invalidateKb(kbId);              // 新增:内容变更使旧缓存失效
        documents.save(doc);
    }

    private DocumentParser pickParser(String filename) {
        String n = filename == null ? "" : filename.toLowerCase();
        if (n.endsWith(".md") || n.endsWith(".markdown") || n.endsWith(".txt")) return new MarkdownParser();
        if (n.endsWith(".pdf")) return new PdfParser();
        if (n.endsWith(".docx")) return new WordParser();
        throw new IllegalArgumentException("Unsupported file type: " + filename);
    }
    
}
