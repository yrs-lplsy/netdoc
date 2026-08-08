package com.kbrag.document;

public enum DocumentStatus {
    PROCESSING(0, "文档处理中"),
    READY(1, "文档就绪"),
    FAILED(2, "文档处理失败");

    private final Integer code;
    private final String desc;

    DocumentStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;    
    }

    public Integer getCode() {
        return code;
    }
    public String getDesc() {
        return desc;
    }
}
