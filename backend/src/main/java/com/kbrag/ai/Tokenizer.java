package com.kbrag.ai;

import java.util.List;

import org.springframework.stereotype.Component;

import com.huaban.analysis.jieba.JiebaSegmenter;

@Component
public class Tokenizer {

    private final JiebaSegmenter segmenter = new JiebaSegmenter();

    /** 中文分词，返回空格分隔字符串，供 PG tsvector 使用。 */
    public String segment(String text) {
        List<String> words = segmenter.sentenceProcess(text).stream()
        .filter(w -> w.length()>1 || w.matches("[\u4e00-\u9fa5a-zA-Z0-9]"))
        .toList();
        return String.join(" ", words);
    }
    
}
