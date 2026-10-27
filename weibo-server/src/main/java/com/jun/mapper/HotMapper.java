package com.jun.mapper;

import com.jun.entity.StatusImage;
import com.jun.vo.HotStatusVO;
import com.jun.vo.HotTopicVO;
import com.jun.vo.UserVO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface HotMapper {

    List<HotTopicVO> getTopics(Integer size);

    List<UserVO> getUsersInfoByUserIds(List<Long> setIds);

    List<StatusImage> getImageByIds(List<Long> ids);

    List<HotStatusVO> getPostByMissIds(List<Long> missIds);

    List<HotStatusVO> getPostByIds(List<Long> originalIds);
}
