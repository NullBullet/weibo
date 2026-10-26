package com.jun.constant;

public class RedisConstants {
    //关注
    public static final String FOLLOW_FOLLOWING_KEY = "follow:following:";
    //被关注
    public static final String FOLLOW_FANS_KEY = "follow:fans:";
    //关注/粉丝集合缓存 TTL：7 天（秒），重建/写入时设置与续期，读路径 miss 时从 user_following/user_follower 表重建
    public static final Long FOLLOW_SET_TTL = 7 * 24 * 60 * 60L;
    //用户统计    user:stats:{userId} -> followingCount, followersCount, likesCount
    public static final String USER_STATS_KEY = "user:stats:";
    //用户统计缓存 TTL：7 天（秒），初始化写入，业务访问时刷新
    public static final Long USER_STATS_TTL = 7 * 24 * 60 * 60L;
    //用户统计重建分布式锁  user:stats:lock:{userId}
    public static final String USER_STATS_LOCK_KEY = "user:stats:lock:";
    //重建锁过期时间（秒），防止持锁端宕机导致死锁
    public static final long USER_STATS_LOCK_TTL_SECONDS = 30L;
    //微博被谁点赞
    public static final String LIKE_POST_KEY = "like:post:";

    //likeCount、commentCount、forwardCount、viewCount、createTime
    public static final String POST_STATS_KEY = "post:stats:";
    //帖子统计缓存 TTL：7 天（秒），初始化写入，业务访问时刷新
    public static final Long POST_STATS_TTL = 7 * 24 * 60 * 60L;
    //帖子统计重建分布式锁  post:stats:lock:{postId}
    public static final String POST_STATS_LOCK_KEY = "post:stats:lock:";
    //重建锁过期时间（秒），防止持锁端宕机导致死锁
    public static final long POST_STATS_LOCK_TTL_SECONDS = 30L;
    //热度排序
    public static final String HOT_POST_ZSET_KEY = "hot:post:zset";
    //热榜候选池
    public static final String HOT_POST_CANDIDATE_KEY = "hot:post:candidate";

    //用户微博id汇总
    public static final String USER_POST_IDS_KEY = "user:post:ids:";
    //用户微博ID缓存 TTL：7 天（秒），写入/重建时设置，业务访问时刷新
    public static final Long USER_POST_IDS_TTL = 7 * 24 * 60 * 60L;
    //微博详情
    public static final String POST_DETAIL_KEY = "post:detail:";
    public static final Long POST_DETAIL_TTL = 1800L;
    public static final Long HOT_POST_DETAIL_TTL = 86400L;

    //用户id、昵称、头像
    public static final String USER_INFO_KEY = "user:info:";
    public static final Long USER_INFO_TTL = 86400L;

    //搜索缓存
    public static final String SEARCH_RESULT_REALTIME_KEY = "search:result:realtime:";
    //综合搜索结果ID缓存    search:ids:comprehensive:{keyword} -> List<Long>
    public static final String SEARCH_IDS_COMPREHENSIVE_KEY = "search:ids:comprehensive:";
    public static final Long SEARCH_RESULT_TTL = 1800L;

    //评论列表缓存
    //一级评论列表    post:comment:list:{postId}:{page}:{pageSize}
    public static final String POST_COMMENT_LIST_KEY = "post:comment:list:";
    //二级回复列表    comment:reply:list:{commentId}:{page}:{pageSize}
    public static final String COMMENT_REPLY_LIST_KEY = "comment:reply:list:";
    //分页缓存常见页兜底 key 后缀
    //":pages" 索引缺失/被淘汰时用于删除默认分页缓存，格式为 :{page}:{pageSize}
    public static final String[] COMMON_PAGE_KEY_SUFFIXES = {":1:3", ":1:5", ":1:10", ":1:20"};
    //评论统计    comment:stats:{commentId} -> replyCount, likeCount
    public static final String COMMENT_STATS_KEY = "comment:stats:";
    //评论统计缓存 TTL：7 天（秒），重建/回填时设置，业务写入时续期
    public static final Long COMMENT_STATS_TTL = 7 * 24 * 60 * 60L;
    //评论统计重建分布式锁  comment:stats:lock:{commentId}
    public static final String COMMENT_STATS_LOCK_KEY = "comment:stats:lock:";
    //重建锁过期时间（秒），防止持锁端宕机导致死锁
    public static final long COMMENT_STATS_LOCK_TTL_SECONDS = 30L;
    //评论被谁点赞
    public static final String LIKE_COMMENT_KEY = "like:comment:";
    //点赞集合缓存 TTL：7 天（秒），重建/写入时设置与续期，读路径 miss 时从 attitudes 表重建
    public static final Long LIKE_SET_TTL = 7 * 24 * 60 * 60L;
    //用户信息缓存    user:profile:{userId}
    public static final String USER_PROFILE_KEY = "user:profile:";
    public static final Long USER_PROFILE_TTL = 3600L;

    public static final String MQ_IDEMPOTENT_KEY = "mq:idempotent:";

    //用户收件箱    feed:user:{userId} -> ZSET (postId, createTime)
    public static final String FEED_USER_KEY = "feed:user:";
    //用户收件箱缓存 TTL：7 天（秒），重建/扇出写入时设置与续期，
    //无独立收件箱表，miss 时由关注对象的最近帖子聚合重建
    public static final Long FEED_USER_TTL = 7 * 24 * 60 * 60L;

    //活跃用户    active_users -> ZSET (userId, loginTimestampMs)
    public static final String ACTIVE_USERS_KEY = "active:users";

    //验证码    captcha:{captchaId} -> code
    public static final String CAPTCHA_KEY = "captcha:";
    public static final Long CAPTCHA_TTL = 300L;

    //验证码IP限制    ip:captcha:{ip} -> count
    public static final String IP_CAPTCHA_KEY = "ip:captcha:";

    //登录IP限制    ip:login:{ip} -> count
    public static final String IP_LOGIN_KEY = "ip:login:";

    //登录失败计数    login:fail:{username} -> count
    public static final String LOGIN_FAIL_KEY = "login:fail:";

    //抽奖活动：statusId -> activityId 映射
    public static final String REWARD_ACTIVITY_STATUS_KEY = "reward:activity:status:";
    //抽奖活动信息：activityId -> Hash {activityType, status, fanOnly, creatorId}
    public static final String REWARD_ACTIVITY_INFO_KEY = "reward:activity:info:";
}
