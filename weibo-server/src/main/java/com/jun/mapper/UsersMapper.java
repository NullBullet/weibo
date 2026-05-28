package com.jun.mapper;

import com.github.pagehelper.Page;
import com.jun.dto.StatusPageQueryDTO;
import com.jun.dto.UserProfileDTO;
import com.jun.entity.StatusImage;
import com.jun.entity.User;
import com.jun.entity.UserStatistics;
import com.jun.vo.StatusVO;
import com.jun.vo.UserCountVO;
import com.jun.vo.UsersInfoVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface UsersMapper {
    @Select("select *from users where id=#{userId}")
    User getUserInfoByUserId(Long userId);

    @Select("select following_count,followers_count,likes_count from user_statistics where user_id=#{userId}")
    UserCountVO countByUserId(Long userId);

    @Select("select id,nickname,avatar,bio,userType from users where id=#{userId}")
    UsersInfoVO getUserInfoWithoutStats(@Param("userId") Long userId);

    int updateProfile(@Param("dto") UserProfileDTO userProfileDTO,@Param("userId") Long userId);

    @Select("select nickname,bio,avatar,gender,birthday,relationship_status,hometown from users where id = #{userId}")
    UserProfileDTO getProfile(Long userId);

    @Update("update users set avatar = #{avatarUrl} where id = #{userId}")
    int updateAvatar(@Param("userId") Long userId, @Param("avatarUrl") String avatarUrl);

    @Select("select avatar from users where id = #{userId}")
    String getAvatarByUserId(Long userId);

    Page<StatusVO> getPostByUserId(StatusPageQueryDTO statusPageQueryDTO);

    List<StatusImage> getImageByIds(List<Long> ids);

    List<StatusVO> getPostByMissIds(List<Long> missIds);

    List<StatusVO> getPostByIds(List<Long> originalIds);
}
