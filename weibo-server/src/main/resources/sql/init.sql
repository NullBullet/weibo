-- ============================================================
-- 微博项目数据库初始化脚本
-- 目标数据库：MySQL 8.x
-- 字符集：utf8mb4
-- 说明：
--   1. 本脚本只负责建库/建表，不包含业务测试数据。
--   2. 使用 CREATE TABLE IF NOT EXISTS，重复执行不会删除已有表。
--   3. 脚本不包含 DROP TABLE；如果已有表结构不一致，请自行做迁移。
--   4. 表定义基于当前 weibo 库实际结构，共 24 张表。
--   5. 表按名称排序；messages、hashtags、status_hashtags、search_histories、
--      system_configurations、verification_codes 当前代码未使用，作为预留表保留。
-- ============================================================

CREATE DATABASE IF NOT EXISTS `weibo`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

USE `weibo`;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;
CREATE TABLE IF NOT EXISTS `attitudes` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '点赞 ID',
  `user_id` bigint unsigned NOT NULL COMMENT '用户 ID',
  `target_type` tinyint unsigned NOT NULL COMMENT '目标类型：1-微博，2-评论',
  `target_id` bigint unsigned NOT NULL COMMENT '目标 ID（微博 ID 或评论 ID）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_target` (`user_id`,`target_type`,`target_id`),
  KEY `idx_attitude_target_created_user` (`target_id`,`target_type`,`created_at`,`user_id`),
  CONSTRAINT `fk_attitude_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='点赞表';
CREATE TABLE IF NOT EXISTS `comments` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '评论 ID',
  `status_id` bigint unsigned NOT NULL COMMENT '微博 ID',
  `user_id` bigint unsigned NOT NULL COMMENT '用户 ID',
  `parent_id` bigint unsigned DEFAULT NULL COMMENT '父评论 ID（回复评论时）',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '评论内容',
  `likes_count` int unsigned DEFAULT '0' COMMENT '点赞数',
  `is_deleted` tinyint unsigned DEFAULT '0' COMMENT '是否删除：0-否，1-是',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `reply_user_id` bigint unsigned DEFAULT NULL COMMENT '被回复用户ID',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_status_created` (`status_id`,`created_at`),
  KEY `idx_comment_parent_deleted_created` (`parent_id`,`is_deleted`,`created_at`),
  CONSTRAINT `fk_comment_status` FOREIGN KEY (`status_id`) REFERENCES `statuses` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_comment_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评论表';
CREATE TABLE IF NOT EXISTS `conversation` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '会话ID',
  `user1_id` bigint NOT NULL COMMENT '参与会话用户1（较小用户ID）',
  `user2_id` bigint NOT NULL COMMENT '参与会话用户2（较大用户ID）',
  `last_message` varchar(500) DEFAULT NULL COMMENT '最后一条消息内容摘要',
  `last_message_time` datetime DEFAULT NULL COMMENT '最后一条消息发送时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_pair` (`user1_id`,`user2_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户私聊会话表';
CREATE TABLE IF NOT EXISTS `conversation_user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `conversation_id` bigint NOT NULL COMMENT '所属会话ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `unread_count` int NOT NULL DEFAULT '0' COMMENT '当前用户未读消息数量',
  `last_read_message_id` bigint DEFAULT NULL COMMENT '最后已读消息ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conversation_user` (`conversation_id`,`user_id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话成员关系表';
CREATE TABLE IF NOT EXISTS `hashtags` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '话题 ID',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '话题名称',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '话题描述',
  `use_count` bigint unsigned DEFAULT '0' COMMENT '使用次数',
  `is_hot` tinyint unsigned DEFAULT '0' COMMENT '是否热门：0-否，1-是',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`),
  KEY `idx_use_count` (`use_count`),
  KEY `idx_is_hot` (`is_hot`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='话题/标签表';
CREATE TABLE IF NOT EXISTS `hot_topics` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '话题 ID',
  `title` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '话题标题',
  `heat_value` bigint unsigned DEFAULT '0' COMMENT '热度值',
  `rank` int unsigned DEFAULT '0' COMMENT '排名',
  `topic_type` tinyint unsigned DEFAULT '0' COMMENT '话题类型：0-普通，1-广告',
  `icon` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '图标标识（如：热、新、爆）',
  `status` tinyint unsigned DEFAULT '1' COMMENT '状态：0-下线，1-上线',
  `start_time` datetime DEFAULT NULL COMMENT '开始时间',
  `end_time` datetime DEFAULT NULL COMMENT '结束时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_rank` (`rank`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='热搜话题表';
CREATE TABLE IF NOT EXISTS `login_logs` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '日志 ID',
  `user_id` bigint unsigned DEFAULT NULL COMMENT '用户 ID',
  `login_type` tinyint unsigned DEFAULT '1' COMMENT '登录方式：1-密码，2-验证码，3-第三方',
  `ip_address` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '登录 IP',
  `user_agent` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户代理',
  `login_status` tinyint unsigned DEFAULT '1' COMMENT '登录状态：0-失败，1-成功',
  `fail_reason` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '失败原因',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登录时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='登录日志表';
CREATE TABLE IF NOT EXISTS `messages` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '消息 ID',
  `user_id` bigint unsigned NOT NULL COMMENT '接收用户 ID',
  `sender_id` bigint unsigned DEFAULT NULL COMMENT '发送用户 ID（系统消息为 NULL）',
  `message_type` tinyint unsigned NOT NULL COMMENT '消息类型：1-评论，2-点赞，3-关注，4-系统，5-私信',
  `target_type` tinyint unsigned DEFAULT NULL COMMENT '目标类型：1-微博，2-评论',
  `target_id` bigint unsigned DEFAULT NULL COMMENT '目标 ID',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '消息内容',
  `is_read` tinyint unsigned DEFAULT '0' COMMENT '是否已读：0-未读，1-已读',
  `read_at` datetime DEFAULT NULL COMMENT '阅读时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_sender_id` (`sender_id`),
  KEY `idx_message_type` (`message_type`),
  KEY `idx_is_read` (`is_read`),
  KEY `idx_created_at` (`created_at`),
  KEY `idx_user_read_created` (`user_id`,`is_read`,`created_at`),
  CONSTRAINT `fk_message_sender` FOREIGN KEY (`sender_id`) REFERENCES `users` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_message_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息表';
CREATE TABLE IF NOT EXISTS `notification_comment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `receiver_id` bigint NOT NULL COMMENT '接收通知用户',
  `sender_id` bigint NOT NULL COMMENT '评论用户',
  `target_type` tinyint NOT NULL COMMENT '1-微博 2-评论',
  `target_id` bigint NOT NULL COMMENT '目标ID',
  `comment_id` bigint NOT NULL COMMENT '新产生的评论ID',
  `is_read` tinyint DEFAULT '0' COMMENT '是否已读',
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_notif_comment_receiver_time` (`receiver_id`,`create_time`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='评论通知';
CREATE TABLE IF NOT EXISTS `notification_like` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `receiver_id` bigint NOT NULL COMMENT '被通知人',
  `target_type` tinyint NOT NULL COMMENT '1-帖子 2-评论',
  `target_id` bigint NOT NULL COMMENT '目标ID',
  `like_count` int DEFAULT '1' COMMENT '累计点赞数',
  `last_like_time` datetime NOT NULL COMMENT '最后点赞时间',
  `is_read` tinyint DEFAULT '0',
  `create_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_receiver_target` (`receiver_id`,`target_type`,`target_id`),
  KEY `idx_notif_like_receiver_time` (`receiver_id`,`last_like_time`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='聚合通知表';
CREATE TABLE IF NOT EXISTS `notification_system` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '接收用户ID',
  `category` varchar(20) NOT NULL COMMENT '通知分类：REWARD 抽奖通知、SYSTEM 系统公告、SECURITY 安全提醒',
  `content` varchar(1000) DEFAULT NULL COMMENT '通知内容',
  `related_id` bigint DEFAULT NULL COMMENT '关联业务ID',
  `related_type` varchar(20) DEFAULT NULL COMMENT '关联业务类型（如 ACTIVITY）',
  `is_read` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否已读：0-未读，1-已读',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_read` (`user_id`,`is_read`),
  KEY `idx_notif_system_user_time` (`user_id`,`create_time`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统通知表';
CREATE TABLE IF NOT EXISTS `private_message` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '消息ID',
  `conversation_id` bigint NOT NULL COMMENT '所属会话ID',
  `sender_id` bigint NOT NULL COMMENT '发送者用户ID',
  `client_message_id` varchar(64) DEFAULT NULL COMMENT '客户端消息唯一标识',
  `receiver_id` bigint NOT NULL COMMENT '接收者用户ID',
  `content` varchar(1000) NOT NULL COMMENT '消息内容',
  `is_read` tinyint NOT NULL DEFAULT '0' COMMENT '是否已读（0未读 1已读）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
  `message_type` tinyint NOT NULL DEFAULT '1' COMMENT '消息类型（1文本 2图片 3文件）',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '消息状态（1正常 2撤回 3删除）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sender_client_msg` (`sender_id`,`client_message_id`),
  KEY `idx_conversation_time` (`conversation_id`,`create_time`),
  KEY `idx_private_receiver_read_conv` (`receiver_id`,`is_read`,`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户私信消息表';
CREATE TABLE IF NOT EXISTS `reward_activity` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `status_id` bigint NOT NULL COMMENT '关联微博ID',
  `creator_id` bigint NOT NULL COMMENT '创建者用户ID',
  `activity_type` int NOT NULL COMMENT '活动类型：1-限量参与，2-时间范围',
  `reward_name` varchar(100) DEFAULT NULL COMMENT '奖品名称',
  `reward_desc` varchar(500) DEFAULT NULL COMMENT '奖品描述',
  `fan_only` tinyint(1) DEFAULT '1' COMMENT '是否仅粉丝参与',
  `participant_limit` int DEFAULT NULL COMMENT '最大参与人数（限量模式）',
  `winner_count` int DEFAULT NULL COMMENT '中奖人数',
  `start_time` datetime DEFAULT NULL COMMENT '开始时间',
  `end_time` datetime DEFAULT NULL COMMENT '结束时间',
  `status` int NOT NULL DEFAULT '1' COMMENT '活动状态：0-未开始，1-进行中，2-已结束',
  `participant_count` int NOT NULL DEFAULT '0' COMMENT '当前参与人数',
  `drawn` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否已开奖：0-否，1-是',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_status_id` (`status_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='抽奖活动表';
CREATE TABLE IF NOT EXISTS `reward_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `activity_id` bigint NOT NULL COMMENT '活动ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `status` int NOT NULL DEFAULT '0' COMMENT '参与状态：0-已参与，1-已中奖，2-已领取',
  `win_time` datetime DEFAULT NULL COMMENT '中奖时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '参与时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_activity_user` (`activity_id`,`user_id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='参与记录表';
CREATE TABLE IF NOT EXISTS `search_histories` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '历史 ID',
  `user_id` bigint unsigned DEFAULT NULL COMMENT '用户 ID（未登录为 NULL）',
  `keyword` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '搜索关键词',
  `search_type` tinyint unsigned DEFAULT '0' COMMENT '搜索类型：0-全部，1-微博，2-用户，3-话题',
  `result_count` int unsigned DEFAULT '0' COMMENT '搜索结果数',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '搜索时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_keyword` (`keyword`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='搜索历史表';
CREATE TABLE IF NOT EXISTS `status_hashtags` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '关联 ID',
  `status_id` bigint unsigned NOT NULL COMMENT '微博 ID',
  `hashtag_id` bigint unsigned NOT NULL COMMENT '话题 ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_status_hashtag` (`status_id`,`hashtag_id`),
  KEY `idx_hashtag_id` (`hashtag_id`),
  CONSTRAINT `fk_status_hashtag_status` FOREIGN KEY (`status_id`) REFERENCES `statuses` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_status_hashtag_tag` FOREIGN KEY (`hashtag_id`) REFERENCES `hashtags` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='微博话题关联表';
CREATE TABLE IF NOT EXISTS `status_images` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '图片 ID',
  `status_id` bigint unsigned NOT NULL COMMENT '微博 ID',
  `image_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '图片 URL',
  `image_order` tinyint unsigned DEFAULT '0' COMMENT '图片排序',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_status_id` (`status_id`),
  CONSTRAINT `fk_status_image` FOREIGN KEY (`status_id`) REFERENCES `statuses` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='微博图片表';
CREATE TABLE IF NOT EXISTS `statuses` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '微博 ID',
  `user_id` bigint unsigned NOT NULL COMMENT '用户 ID',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '微博内容',
  `status_type` tinyint unsigned DEFAULT '0' COMMENT '微博类型：0-普通，1-转发，2-回复',
  `original_status_id` bigint unsigned DEFAULT NULL COMMENT '原微博 ID（转发/回复时）',
  `is_top` tinyint unsigned DEFAULT '0' COMMENT '是否置顶：0-否，1-是',
  `is_deleted` tinyint unsigned DEFAULT '0' COMMENT '是否删除：0-否，1-是',
  `comments_count` int unsigned DEFAULT '0' COMMENT '评论数',
  `attitudes_count` int unsigned DEFAULT '0' COMMENT '点赞数',
  `reposts_count` int unsigned DEFAULT '0' COMMENT '转发数',
  `views_count` int unsigned DEFAULT '0' COMMENT '浏览量',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_created_at` (`created_at`),
  KEY `idx_status_user_deleted_created` (`user_id`,`is_deleted`,`created_at`),
  CONSTRAINT `fk_status_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='微博表';
CREATE TABLE IF NOT EXISTS `system_configurations` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '配置 ID',
  `config_key` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '配置键',
  `config_value` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '配置值',
  `config_type` tinyint unsigned DEFAULT '0' COMMENT '配置类型：0-字符串，1-数字，2-布尔，3-JSON',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '配置说明',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统配置表';
CREATE TABLE IF NOT EXISTS `user_follower` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `followee_id` bigint unsigned NOT NULL COMMENT '被关注者ID (谁被关注)',
  `follower_id` bigint unsigned NOT NULL COMMENT '关注者ID (谁发起的关注)',
  `status` tinyint unsigned NOT NULL DEFAULT '1' COMMENT '状态: 0-正常, 1-拉黑',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '关注时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_followee_follower` (`followee_id`,`follower_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户粉丝关系表（反向）';
CREATE TABLE IF NOT EXISTS `user_following` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `follower_id` bigint unsigned NOT NULL COMMENT '关注者ID (谁发起的关注)',
  `followee_id` bigint unsigned NOT NULL COMMENT '被关注者ID (关注了谁)',
  `status` tinyint unsigned NOT NULL DEFAULT '1' COMMENT '状态: 0-正常, 1-拉黑',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '关注时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_follower_followee` (`follower_id`,`followee_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户关注关系表（正向）';
CREATE TABLE IF NOT EXISTS `user_statistics` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '统计 ID',
  `user_id` bigint unsigned NOT NULL COMMENT '用户 ID',
  `following_count` int unsigned DEFAULT '0' COMMENT '关注数',
  `followers_count` int unsigned DEFAULT '0' COMMENT '粉丝数',
  `statuses_count` int unsigned DEFAULT '0' COMMENT '微博数',
  `likes_count` int unsigned DEFAULT '0' COMMENT '获赞数',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_id` (`user_id`),
  CONSTRAINT `fk_statistics_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户统计信息表';
CREATE TABLE IF NOT EXISTS `users` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '用户 ID',
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户名（登录账号）',
  `password` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '密码（加密存储）',
  `nickname` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '昵称',
  `avatar` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '头像 URL',
  `gender` tinyint unsigned DEFAULT '0' COMMENT '性别：0-保密，1-男，2-女',
  `birthday` date DEFAULT NULL COMMENT '生日',
  `bio` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '个人简介（最多 140 字）',
  `relationship_status` tinyint unsigned DEFAULT '0' COMMENT '感情状况：0-不选，1-单身，2-恋爱中，3-已婚',
  `hometown` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '家乡',
  `phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '手机号',
  `email` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '邮箱',
  `status` tinyint unsigned DEFAULT '1' COMMENT '账号状态：0-禁用，1-正常，2-待验证',
  `last_login_time` datetime DEFAULT NULL COMMENT '最后登录时间',
  `last_login_ip` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最后登录 IP',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at` datetime DEFAULT NULL COMMENT '删除时间（软删除）',
  `userType` tinyint NOT NULL DEFAULT '0' COMMENT '用户类型：0-普通用户，1-大V用户',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  UNIQUE KEY `uk_phone` (`phone`),
  UNIQUE KEY `uk_email` (`email`),
  KEY `idx_nickname` (`nickname`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';
CREATE TABLE IF NOT EXISTS `verification_codes` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '验证码 ID',
  `target` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '目标（手机号或邮箱）',
  `code` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '验证码',
  `code_type` tinyint unsigned NOT NULL COMMENT '验证码类型：1-注册，2-登录，3-找回密码，4-绑定手机/邮箱',
  `is_used` tinyint unsigned DEFAULT '0' COMMENT '是否已使用：0-否，1-是',
  `expires_at` datetime NOT NULL COMMENT '过期时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_target` (`target`),
  KEY `idx_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='验证码表';
SET FOREIGN_KEY_CHECKS = 1;