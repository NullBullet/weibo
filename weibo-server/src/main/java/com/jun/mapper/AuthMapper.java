package com.jun.mapper;

import com.jun.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AuthMapper {
    @Select("select *from users where username=#{username}")
    User getByUsername(String username);
}
