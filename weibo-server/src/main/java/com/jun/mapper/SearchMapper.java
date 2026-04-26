package com.jun.mapper;

import com.github.pagehelper.Page;
import com.jun.dto.SearchResultDTO;
import com.jun.entity.StatusImage;
import com.jun.vo.HotStatusVO;
import com.jun.vo.StatusVO;
import com.jun.vo.UserVO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Set;

@Mapper
public interface SearchMapper {
    Page<HotStatusVO> getResults(SearchResultDTO searchResultDTO);

    List<UserVO> getUsersInfoByUserIds(Set<Long> setIds);

    List<StatusImage> getImageByIds(List<Long> statusIds);
}
