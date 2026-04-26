package com.jun.service;

import com.jun.dto.SearchResultDTO;
import com.jun.result.Result;

public interface SearchService {
    Result results(SearchResultDTO searchResultDTO);
}
