package com.jun.mapper;

import com.jun.WeiboServerApplicationTests;
import com.jun.entity.Statuses;
import com.jun.entity.User;
import com.jun.entity.UserStatistics;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Set;

@Mapper
public interface TestMapper {
    @Select("select *from statuses where user_id=#{userId} order by created_at desc")
    List<Statuses> getStatusByUserId(Long userId);

    @Select("select * from users")
    Set<Long> getUserId();

    @Select("select *from statuses")
    List<Statuses> selectList(Object o);

    @Select("select *from users")
    List<User> selectListusers(Object o);

    boolean insertBatchSomeColumn(List<UserStatistics> statsListToSave);
}
