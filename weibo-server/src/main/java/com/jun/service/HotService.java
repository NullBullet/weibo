package com.jun.service;
import com.jun.dto.PageQueryDTO;
import com.jun.result.PageResult;
import com.jun.result.Result;
import com.jun.vo.HotStatusVO;
import com.jun.vo.HotTopicVO;

import java.util.List;

public interface HotService {
    Result<List<HotTopicVO>> topics(Integer size);

    Result<PageResult> getPosts(PageQueryDTO pageQueryDTO);
}
