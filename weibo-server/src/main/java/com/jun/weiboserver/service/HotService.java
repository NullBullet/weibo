package com.jun.weiboserver.service;
import com.jun.result.Result;
import com.jun.vo.HotTopicVO;

import java.util.List;

public interface HotService {
    Result<List<HotTopicVO>> topics(Integer size);
}
