package com.jun.weiboserver.mapper;

import com.jun.entity.User;
import com.jun.entity.UserStatistics;
import com.jun.vo.UserCountVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UsersMapper {
    @Select("select *from users where id=#{userId}")
    User getUserInfoByUserId(Long userId);

    @Select("select following_count,followers_count,likes_count from user_statistics where user_id=#{userId}")
    UserCountVO countByUserId(Long userId);
}
