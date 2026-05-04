package com.jun.constant;

public class RedisConstants {
    //关注
    public static final String FOLLOW_FOLLOWING_KEY = "follow:following:";
    //被关注
    public static final String FOLLOW_FANS_KEY = "follow:fans:";
    //关注数量
    public static final String COUNT_FOLLOWING_KEY = "count:following:";
    //被关注数量
    public static final String COUNT_FANS_KEY = "count:fans:";
    //微博被谁点赞
    public static final String LIKE_POST_KEY = "like:post:";

    //待废弃
    public static final String LIKE_COUNT_POST_KEY = "like:count:post:";
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
}
