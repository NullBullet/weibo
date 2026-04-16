package com.jun.weiboserver.service;
import com.jun.dto.StatusPageQueryDTO;
import com.jun.dto.UserProfileDTO;
import com.jun.result.PageResult;
import com.jun.result.Result;
import com.jun.vo.UsersInfoVO;
import org.springframework.web.multipart.MultipartFile;

public interface UsersService {
    Result<UsersInfoVO> me();

    Result<String> updateProfile(UserProfileDTO userProfileDTO,MultipartFile avatarFile);

    Result<UserProfileDTO> getProfile();

    Result<PageResult> getPost(StatusPageQueryDTO StatusPageQueryDTO);
}
