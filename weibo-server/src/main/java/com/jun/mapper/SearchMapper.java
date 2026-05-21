package com.jun.mapper;

import com.github.pagehelper.Page;
import com.jun.dto.SearchResultDTO;
import com.jun.dto.SearchUsersDTO;
import com.jun.entity.StatusImage;
import com.jun.vo.HotStatusVO;
import com.jun.vo.SearchStatusVO;
import com.jun.vo.SearchUserVO;
import com.jun.vo.UserVO;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Mapper
public interface SearchMapper {
    Page<HotStatusVO> getResults(SearchResultDTO searchResultDTO);

    List<UserVO> getUsersInfoByUserIds(List<Long> setIds);

    List<StatusImage> getImageByIds(List<Long> statusIds);

    Page<SearchUserVO> getUsers(SearchUsersDTO searchUsersDTO);

    List<Long> getResultIds(SearchResultDTO searchResultDTO);

    List<SearchStatusVO> getPostByIds(List<Long> missIds);

    Long countResults(String keyword);

    Long countResultsByTime(String keyword, LocalDateTime startDate, LocalDateTime endDate);

    List<Long> getComprehensiveResultIds(String keyword, LocalDateTime startDate, LocalDateTime endDate);

    List<SearchStatusVO> getPostByOriginalIds(List<Long> originalIds);
}
