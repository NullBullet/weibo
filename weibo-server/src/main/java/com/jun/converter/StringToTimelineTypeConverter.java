package com.jun.converter;

import com.jun.enumeration.TimelineType;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class StringToTimelineTypeConverter implements Converter<String, TimelineType> {

    @Override
    public TimelineType convert(String source) {
        try {
            int code = Integer.parseInt(source);
            return TimelineType.fromValue(code);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("无效的时间线类型: " + source, e);
        }
    }
}