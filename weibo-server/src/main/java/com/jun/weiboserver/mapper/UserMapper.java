package com.jun.weiboserver.mapper;

import com.jun.vo.UserInfoVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {
    @Select("select *from users where id=#{userId}")
    UserInfoVO getById(Long userId);
}
