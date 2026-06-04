package com.jun.constant;

public class RedisConstants {
    //关注
    public static final String FOLLOW_FOLLOWING_KEY = "follow:following:";
    //被关注
    public static final String FOLLOW_FANS_KEY = "follow:fans:";
    //用户统计    user:stats:{userId} -> followingCount, followersCount, likesCount
    public static final String USER_STATS_KEY = "user:stats:";
    //微博被谁点赞
    public static final String LIKE_POST_KEY = "like:post:";

    //likeCount、commentCount、forwardCount、viewCount、createTime
    public static final String POST_STATS_KEY = "post:stats:";
    //热度排序
    public static final String HOT_POST_ZSET_KEY = "hot:post:zset";
    //热榜候选池
    public static final String HOT_POST_CANDIDATE_KEY = "hot:post:candidate";

    //用户微博id汇总
    public static final String USER_POST_IDS_KEY = "user:post:ids:";
    //微博详情
    public static final String POST_DETAIL_KEY = "post:detail:";
    public static final Long POST_DETAIL_TTL = 1800L;
    public static final Long HOT_POST_DETAIL_TTL = 86400L;

    //用户id、昵称、头像
    public static final String USER_INFO_KEY = "user:info:";
    public static final Long USER_INFO_TTL = 86400L;

    //搜索缓存
    public static final String SEARCH_RESULT_REALTIME_KEY = "search:result:realtime:";
    public static final String SEARCH_RESULT_COMPREHENSIVE_KEY = "search:result:comprehensive:";
    public static final Long SEARCH_RESULT_TTL = 1800L;

    //一级评论列表    post:comment:list:{postId}:{page}:{pageSize}
    public static final String POST_COMMENT_LIST_KEY = "post:comment:list:";
    //二级回复列表    comment:reply:list:{commentId}:{page}:{pageSize}
    public static final String COMMENT_REPLY_LIST_KEY = "comment:reply:list:";
    //评论统计    comment:stats:{commentId} -> replyCount, likeCount...
    public static final String COMMENT_STATS_KEY = "comment:stats:";
    //评论被谁点赞
    public static final String LIKE_COMMENT_KEY = "like:comment:";
    //用户信息缓存    user:profile:{userId}
    public static final String USER_PROFILE_KEY = "user:profile:";
    public static final Long USER_PROFILE_TTL = 3600L;

    public static final String MQ_IDEMPOTENT_KEY = "mq:idempotent:";
}
