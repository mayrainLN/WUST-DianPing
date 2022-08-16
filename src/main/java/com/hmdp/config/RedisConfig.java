package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author :MayRain
 * @version :1.0
 * @date :2022/8/14 13:03
 * @description :
 */
@Configuration
public class RedisConfig {
    @Bean
    public RedissonClient redissonClient(){
        // 配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://localhost:6379");
        // 创建 RedissonClient对象
        return Redisson.create(config);
    }
}
