package com.jun.mapper;

import com.jun.entity.Statuses;
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
}
