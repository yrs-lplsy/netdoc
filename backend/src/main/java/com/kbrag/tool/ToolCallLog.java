package com.kbrag.tool;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Agent 工具调用审计日志(spec §6 tool_call_log)。
 * idempotentKey = conversationId + ":" + agentStepId:幂等键,唯一索引防重复执行
 * (spec §9 双层防线:Python 内存层 + Java DB 层)。
 */
@Data
@Entity
@Table(name = "tool_call_log",
       uniqueConstraints = @UniqueConstraint(name = "uk_tool_call_idem", columnNames = "idempotent_key"))
public class ToolCallLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    // 会话 ID；一次 Agent 对话会话的唯一编号，用来区分不同聊天会话。
    private Long conversationId;
    // 本次调用的工具名称，例如：search_db、read_file、http_request，用来记录是什么工具被调用。
    private String toolName;
    // 幂等键，拼接规则 会话ID:Agent步骤ID
    private String idempotentKey;   // conversationId:agentStepId;网络重试/Agent 重复调用时幂等返回
    @Column(columnDefinition = "text")
    // MySQL varchar 有长度上限（默认 4k），工具入参 JSON 字符串可能很长，因此用无长度上限 text 存储工具调用入参 JSON。
    private String inputJson;
    // 工具调用返回结果摘要，同样是超长文本，text 类型存储。
    @Column(columnDefinition = "text")
    private String outputSummary;
    // 工具调用耗时，单位毫秒；Integer 可空，失败的时候可以不赋值。
    private Integer latencyMs;
    // 布尔标记，本次工具调用是否成功。
    private Boolean ok;
    private LocalDateTime createdAt = LocalDateTime.now();
}
