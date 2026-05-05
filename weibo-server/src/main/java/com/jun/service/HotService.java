package com.jun.service;
import com.jun.dto.ScrollDTO;
import com.jun.result.Result;
import com.jun.result.ScrollResult;
import com.jun.vo.HotTopicVO;

import java.util.List;

public interface HotService {
    Result<List<HotTopicVO>> topics(Integer size);

    Result<ScrollResult> getPosts(ScrollDTO scrollDTO, Long currentUserId);
}
