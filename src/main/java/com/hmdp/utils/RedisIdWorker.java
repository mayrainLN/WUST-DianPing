package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * @author :MayRain
 * @version :1.0
 * @date :2022/8/12 14:11
 * @description :
 */
@Component
public class RedisIdWorker {
    /**
     * 开始时间戳
     */
    public static final long BEGIN_TIMESTAMP = 1640995200L;

    /**
     * 序列号的位数
     */
    public static final int COUNT_BITS = 32;

    @Resource
    StringRedisTemplate stringRedisTemplate;

    public long nextId(String keyPreFix) {
        // 0 传入前缀，用于区分不同业务
        // 1 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSecond - BEGIN_TIMESTAMP;

        // 2 生成序列号
        // 2.1 获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2 自增长（键名加上了时间，便于统计、防止键名自增到上线
//        icr:order:2022:08:12 -> 30300
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPreFix + ":" + date);

        // 3 拼接、返回（二进制移位运算，或运算）
//        0001 0010 0110 1101 0010 0001 0001 0000 0000 0000 0000 0000 0001 0011 1010
//        - **符号位**：1bit，永远为0
//        - **时间戳**：31bit，以秒为单位，可以使用69年
//        - **序列号**：32bit，秒内的计数器，支持每秒产生2^32个不同ID
        return timeStamp << COUNT_BITS | count;
    }

//    public static void main(String[] args) {
//        LocalDateTime time = LocalDateTime.of(2022, 1, 1, 0, 0, 0);
//        Long second = time.toEpochSecond(ZoneOffset.UTC);
//        System.out.println(second);
//    }
}
