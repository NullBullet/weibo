package com.jun.enumeration;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Getter;

public enum TimelineType {
    HOT(1, "热门"),
    LATEST(2, "最新");

    @Getter
    private final int code;
    private final String desc;

    TimelineType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    @JsonCreator
    public static TimelineType fromValue(Integer code) {
        if (code == null) {
            return LATEST;
        }
        for (TimelineType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return LATEST;
    }
}
