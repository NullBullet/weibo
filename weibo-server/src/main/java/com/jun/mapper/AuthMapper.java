package com.jun.mapper;

import com.jun.entity.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AuthMapper {
    @Select("select *from users where username=#{username}")
    User getByUsername(String username);

    @Insert("insert into users(username, password, nickname, avatar) values(#{username}, #{password}, #{nickname}, #{avatar})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int saveUser(User user);
}
