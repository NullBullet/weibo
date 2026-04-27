package com.jun.service;

import com.jun.dto.SearchResultDTO;
import com.jun.dto.SearchUsersDTO;
import com.jun.result.Result;

public interface SearchService {
    Result results(SearchResultDTO searchResultDTO);

    Result users(SearchUsersDTO searchUsersDTO);
}
