package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

/**
 * @author :MayRain
 * @version :1.0
 * @date :2022/8/7 20:26
 * @description :
 */
//妈的刚刚没加注解 ，配置类没生效
@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //登录验证拦截器
        registry.addInterceptor(new LoginInterceptor()).excludePathPatterns( "/shop/**",
                "/voucher/**",
                "/shop-type/**", //店铺
                "/upload/**",
                "/blog/hot",
                "/user/code",
                "/user/login").order(1);

//        拦截所有请求，刷新token
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).addPathPatterns("/**").order(0);
    }
}
