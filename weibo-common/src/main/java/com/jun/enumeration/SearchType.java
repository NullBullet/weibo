package com.jun.enumeration;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Getter;

public enum SearchType {
    COMPREHENSION(0,"综合"),
    REALTIME(1,"实时");

    @Getter
    private final int code;
    private final String desc;

    SearchType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    @JsonCreator
    public static SearchType fromValue(int code){
        for (SearchType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("数据非法");
    }
}
