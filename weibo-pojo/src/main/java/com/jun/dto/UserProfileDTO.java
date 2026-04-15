package com.jun.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDate;


@Data
public class UserProfileDTO {
    private String nickname;
    private String bio;
    private Integer gender;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate birthday;
    @JsonProperty("relationship_status")
    private Integer relationshipStatus;
    private String avatar;
    private String hometown;
}
