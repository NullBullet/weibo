package com.jun.weiboserver.service.impl;

import cn.hutool.core.util.StrUtil;
import com.jun.dto.UserProfileDTO;
import com.jun.entity.User;
import com.jun.entity.UserStatistics;
import com.jun.result.Result;
import com.jun.utils.OssUtil;
import com.jun.vo.UserCountVO;
import com.jun.vo.UsersInfoVO;
import com.jun.weiboserver.mapper.UsersMapper;
import com.jun.weiboserver.service.UsersService;
import com.jun.context.BaseContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class UsersServiceImpl implements UsersService {

    @Resource
    private UsersMapper usersMapper;
    @Resource
    private OssUtil ossUtil;

    @Override
    public Result<UsersInfoVO> me() {
        Long userId = BaseContext.getCurrentId();
        User user = usersMapper.getUserInfoByUserId(userId);
        UsersInfoVO usersInfoVO = new UsersInfoVO();

        UserCountVO userCountVO = usersMapper.countByUserId(userId);

        BeanUtils.copyProperties(user, usersInfoVO);

        usersInfoVO.setStats(userCountVO);
        return Result.success(usersInfoVO);
    }

    @Override
    public Result<String> updateProfile(UserProfileDTO userProfileDTO,MultipartFile avatarFile) {
        Long userId = BaseContext.getCurrentId();
        String fileUrl = null;
        if (avatarFile != null && !avatarFile.isEmpty()) {

            try {
                String avatarPath = usersMapper.getAvatarByUserId(userId);
                List<String> split = StrUtil.split(avatarPath, "/");
                List<String> collect = split.stream().skip(3).toList();
                avatarPath = StrUtil.join("/", collect);
                ossUtil.delete(avatarPath);
            } catch (Exception e) {
                log.error("文件删除失败");
            }

            try {
                String folder = "weibo/avatars/";
                String originalFilename = avatarFile.getOriginalFilename();
                String extension = originalFilename.substring(originalFilename.lastIndexOf("."));
                String objectName = folder + UUID.randomUUID().toString() + extension;

                fileUrl = ossUtil.upload(avatarFile.getInputStream(), objectName);
            } catch (Exception e) {
                log.error("文件上传失败");
            }
        }

        if (userProfileDTO == null) {
            return Result.error("对象为空");
        }
        userProfileDTO.setAvatar(fileUrl);
        int isSuccess = usersMapper.updateProfile(userProfileDTO,userId);
        if (isSuccess != 1){
            return Result.error("更新个人信息失败！");
        }
        return Result.success(fileUrl);
    }

    @Override
    public Result<UserProfileDTO> getProfile() {
        Long userId = BaseContext.getCurrentId();
        UserProfileDTO userProfileDTO = usersMapper.getProfile(userId);
        return Result.success(userProfileDTO);
    }

/*    @Override
    public Result<String> uploadAvatar(MultipartFile file) {
        if (file.isEmpty()) {
            return Result.error("上传文件不能为空");
        }
        try {
            String folder = "weibo/avatars/";
            String originalFilename = file.getOriginalFilename();
            String extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            String objectName = folder+UUID.randomUUID().toString() + extension;

            String fileUrl = ossUtil.upload(file.getInputStream(), objectName);
            return Result.success(fileUrl);
        } catch (Exception e) {
            log.error("文件上传失败");
        }
        return Result.error("文件上传失败");
    }*/
}
