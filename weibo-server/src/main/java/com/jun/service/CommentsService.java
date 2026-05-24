package com.jun.service;

import com.jun.dto.CommentsResultDTO;
import com.jun.dto.PostCommentsDTO;
import com.jun.dto.RepliesCommentsResultDTO;
import com.jun.result.Result;

public interface CommentsService {
    Result getComments(CommentsResultDTO commentsResultDTO);

    Result postComments(PostCommentsDTO postCommentsDTO);

    Result getReplies(RepliesCommentsResultDTO repliesCommentsResultDTO);
}
