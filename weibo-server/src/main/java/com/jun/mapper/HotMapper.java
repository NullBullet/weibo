package com.jun.mapper;

import com.github.pagehelper.Page;
import com.jun.entity.StatusImage;
import com.jun.vo.HotStatusVO;
import com.jun.vo.HotTopicVO;
import com.jun.vo.UserVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface HotMapper {

    List<HotTopicVO> getTopics(Integer size);

    @Select("select *from statuses")
    Page<HotStatusVO> getPosts();

    List<UserVO> getUsersInfoByUserIds(List<Long> ids);

    List<StatusImage> getImageByIds(List<Long> ids);
}
