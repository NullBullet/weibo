package com.jun.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class NotifyUnreadCountVO {

    private String type;

    private Long count;
}