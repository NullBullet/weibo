package com.jun.weiboserver.mapper;

import com.jun.vo.HotTopicVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface HotMapper {

    List<HotTopicVO> getTopics(Integer size);

}
