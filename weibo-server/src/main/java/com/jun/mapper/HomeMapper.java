package com.jun.mapper;

import com.jun.entity.StatusImage;
import com.jun.vo.UserVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Set;

@Mapper
public interface HomeMapper {
    @Select("select followee_id from user_following where follower_id = #{userId}")
    List<Long> getFollowing(Long userId);

    List<UserVO> getUsersInfoByUserIds(Set<Long> setIds);

    List<StatusImage> getImageByIds(List<Long> statusIds);
}
