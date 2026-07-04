package com.jun.service;

import com.jun.dto.StatusesImageDTO;
import com.jun.result.Result;

public interface StatusesService {
    Result saveStatus(StatusesImageDTO statusesImageDTO);

    Result deleteStatus(Long statusId);
}
