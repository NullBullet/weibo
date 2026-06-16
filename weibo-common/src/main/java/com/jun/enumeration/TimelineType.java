package com.jun.enumeration;

public enum TimelineType {
    HOT(1, "热门"),
    LATEST(2, "最新");

    private final int code;
    private final String desc;

    TimelineType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public static TimelineType fromValue(Integer code) {
        if (code == null) {
            return LATEST; // 默认最新
        }
        for (TimelineType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return LATEST;
    }
}
