package com.jun.dto;

import com.jun.entity.StatusImage;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class StatusesImageDTO implements Serializable {
    private String content;

    private List<StatusImage> images;
}
