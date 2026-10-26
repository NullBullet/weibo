package com.jun.enumeration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

public enum TargetType {
    POST(1, "帖子点赞"),
    COMMENT(2, "评论点赞");

    @Getter
    private final int code;
    private final String desc;

    TargetType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    @JsonCreator
    public static TargetType fromValue(Integer code) {
        if (code == null) {
            throw new IllegalArgumentException("targetType 不能为空");
        }
        for (TargetType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("targetType 非法: " + code);
    }

    @JsonValue
    public int getCode() {
        return code;
    }
}
