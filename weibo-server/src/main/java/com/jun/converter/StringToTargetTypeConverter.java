package com.jun.converter;

import com.jun.enumeration.TargetType;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class StringToTargetTypeConverter implements Converter<String, TargetType> {

    @Override
    public TargetType convert(String source) {
        try {
            int code = Integer.parseInt(source);
            return TargetType.fromValue(code);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("无效的 targetType: " + source, e);
        }
    }
}
