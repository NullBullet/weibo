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

    @org.apache.ibatis.annotations.Update("update user_statistics set following_count = #{followingCount}, followers_count = #{followersCount} where user_id = #{userId}")
    int updateUserFollowCounts(@org.apache.ibatis.annotations.Param("userId") Long userId,
                                @org.apache.ibatis.annotations.Param("followingCount") int followingCount,
                                @org.apache.ibatis.annotations.Param("followersCount") int followersCount);
}
