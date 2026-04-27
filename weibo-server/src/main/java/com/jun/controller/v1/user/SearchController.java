package com.jun.controller.v1.user;

import com.jun.dto.SearchResultDTO;
import com.jun.dto.SearchUsersDTO;
import com.jun.enumeration.SearchType;
import com.jun.result.Result;
import com.jun.service.SearchService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/v1/search")
public class SearchController {

    @Resource
    private SearchService searchService;

    @GetMapping("/weibos")
    public Result results(SearchResultDTO searchResultDTO){
        SearchType type = searchResultDTO.getSearchType();
        switch (type){
            case COMPREHENSION:
                return searchService.results(searchResultDTO);
            case REALTIME://从新到旧
                break;
        }
        return null;
    }

    @GetMapping("/users")
    public Result users(SearchUsersDTO searchUsersDTO){
        log.info("搜索用户{}",searchUsersDTO);
        return searchService.users(searchUsersDTO);
    }

}
