package com.jun.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class RepostDTO implements Serializable {
    private String content;

    private Long postId;
}
