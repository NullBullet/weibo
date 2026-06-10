package com.jun.mapper;

import com.jun.entity.LoginLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LoginLogMapper {

    @Insert("insert into login_logs(user_id, login_type, ip_address, user_agent, login_status, fail_reason) " +
            "values(#{userId}, #{loginType}, #{ipAddress}, #{userAgent}, #{loginStatus}, #{failReason})")
    void insert(LoginLog loginLog);
}
