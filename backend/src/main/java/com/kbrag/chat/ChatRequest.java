package com.kbrag.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天请求 DTO。
 * Lombok @Data = @Getter + @Setter + @ToString + @EqualsAndHashCode（字段全 private，靠 Lombok 生成访问器）
 * @Builder 生成链式构造；@NoArgsConstructor/@AllArgsConstructor 生成两个构造器。
 * Jackson 反序列化 JSON body 需要：无参构造器 + setter（@Data 已提供）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {
    private String message;         // 用户提问
    private Long conversationId;    // 多轮会话 ID；null 表示新建会话
}
