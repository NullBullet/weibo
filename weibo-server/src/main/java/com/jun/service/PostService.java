package com.jun.service;

import com.jun.dto.RepostDTO;
import com.jun.dto.ToggleLikeDTO;
import com.jun.result.Result;

public interface PostService {
    Result togglePostLike(ToggleLikeDTO toggleLikeDTO);

    Result repost(RepostDTO repostDTO);
}
