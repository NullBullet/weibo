package com.jun.mapper;

import com.github.pagehelper.Page;
import com.jun.dto.HomeQueryDTO;
import com.jun.entity.StatusImage;
import com.jun.vo.HomeStatusVO;
import com.jun.vo.UserVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Set;

@Mapper
public interface HomeMapper {
    Page<HomeStatusVO> getPosts(@Param("query") HomeQueryDTO homeQueryDTO,
                                @Param("followingIds") List<Long> followingIds);

    @Select("select followee_id from user_following where follower_id = #{userId}")
    List<Long> getFollowing(Long userId);

    List<UserVO> getUsersInfoByUserIds(Set<Long> setIds);

    List<StatusImage> getImageByIds(List<Long> statusIds);
}
