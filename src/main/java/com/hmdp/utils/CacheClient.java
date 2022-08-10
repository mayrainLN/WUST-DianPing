package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * @author :MayRain
 * @version :1.0
 * @date :2022/8/10 14:24
 * @description :
 */
@Component
@Slf4j
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate= stringRedisTemplate;
    }

    // 设将对象存入redis，设置TTL作为缓存不一致的兜底（解决缓存穿透）
    public void set(String key , Object value, Long time , TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    // 设将对象存入redis，设置逻辑过期时间 （解决缓存击穿）
    public void setWithLogicalExpire(String key , Object value, Long time , TimeUnit unit){
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        // 写入redis 没有TTL
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value));
    }

    // 解决缓存穿透
    public <R,ID> R queryWithPassThrough(
            String keyPreFix, ID id , Class<R> type , Function<ID,R> dbFallBack, Long time ,TimeUnit unit
    ) {
        String key = keyPreFix+ id;

        // 先从rerdis查询
        String shopJSON = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJSON)) {
            // 查到了，直接返回
            return JSONUtil.toBean(shopJSON, type);
        }

        // 命中的是空值
        if (shopJSON !=null){
            return null;
        }
        // 缓存未命中，查询数据库
        R r = dbFallBack.apply(id);

        if (r == null) {
            // 解决缓存穿透，将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            // 返回错误信息
            return null;
        }

        // 存在，写入redis
        this.set(key,r,time,unit);
        return r;
    }

    //通过逻辑过期解决缓存击穿问题的 店铺查询
    public <R,ID> R queryWithLogicalExpire(
            String keyPreFix,ID id, Class<R> type, Function<ID,R> dbFallBack,Long time ,TimeUnit unit
    ) {
        // 加入前缀，分门别类
        String key = CACHE_SHOP_KEY + id;

        // 先从rerdis查询
        String shopJSON = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(shopJSON)) {
            // 未命中缓存，直接返回
            return null;
        }

        // 缓存命中，判断是否逻辑过期
        //反序列化
        RedisData redisData = JSONUtil.toBean(shopJSON, RedisData.class);
        JSONObject jsonObject = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(jsonObject, type);
        // 判断是否过期
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            // 过期时间在现在之后，即未过期
            // 直接返回
            return r;
        }
        // 已过期，需要缓存重建
        // 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean hasGetLock = tryLock(lockKey);
        if (hasGetLock) {
            // 成功获取 ， 开启独立线程，实现缓存重建
            // 通过线程池去操作
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 缓存重建
                    R r1 = dbFallBack.apply(id);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 失败，直接返回过期的缓存信息

        return r;
    }

    public <R, ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(shopJson, type);
        }
        // 判断命中的是否是空值
        if (shopJson != null) {
            // 返回一个错误信息
            return null;
        }

        // 4.实现缓存重建
        // 4.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        R r = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 4.2.判断是否获取成功
            if (!isLock) {
                // 4.3.获取锁失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
            }
            // 4.4.获取锁成功，根据id查询数据库
            r = dbFallback.apply(id);
            // 5.不存在，返回错误
            if (r == null) {
                // 将空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                // 返回错误信息
                return null;
            }
            // 6.存在，写入redis
            this.set(key, r, time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            // 7.释放锁
            unlock(lockKey);
        }
        // 8.返回
        return r;
    }

    // 用redis实现锁
    // 尝试获取锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        // 直接返回时，会自动拆箱，拆箱可能出现空指针
        return BooleanUtil.isTrue(flag); //flag为真则返回真
    }

    // 释放锁
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

}
