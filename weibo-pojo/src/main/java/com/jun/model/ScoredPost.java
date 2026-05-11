package com.jun.model;

import com.jun.vo.SearchStatusVO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class ScoredPost {

    private SearchStatusVO post;

    private Double finalScore;
}