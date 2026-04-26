// StringToSearchTypeConverter.java
package com.jun.converter;

import com.jun.enumeration.SearchType;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component // 将其注册为 Spring Bean，方便后续注入
public class StringToSearchTypeConverter implements Converter<String, SearchType> {

    @Override
    public SearchType convert(String source) {
        try {
            // 将前端传来的字符串 "0" 解析为整数 0
            int code = Integer.parseInt(source);
            // 然后调用你已经在枚举里写好的 fromValue 方法进行转换
            return SearchType.fromValue(code);
        } catch (NumberFormatException e) {
            // 如果传入的不是数字，抛出异常
            throw new IllegalArgumentException("无效的搜索类型: " + source, e);
        }
    }
}