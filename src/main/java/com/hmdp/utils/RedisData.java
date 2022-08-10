package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;
//利用逻辑过期解决缓存击穿问题
@Data
// 为了让存入缓存的实体类有一个过期时间的字段
// 相比于让Shop实体类继承RedisData，这样写侵入性更低；
public class RedisData {
    private LocalDateTime expireTime;
    // 可能不止商铺一种实体需要这个工具类，所以用Object
    private Object data;
}

