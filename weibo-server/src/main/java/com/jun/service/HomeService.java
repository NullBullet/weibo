package com.jun.service;

import com.jun.dto.HomeQueryDTO;
import com.jun.result.Result;

public interface HomeService {
    Result getPosts(HomeQueryDTO homeQueryDTO);
}
