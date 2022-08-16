package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * @author :MayRain
 * @version :1.0
 * @date :2022/8/13 17:18
 * @description :
 */
public class SimpleRedisLock implements ILock {
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    private String name;

    public static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 尝试获取锁
     *
     * @param timeoutSec
     * @return true 获取成功   false 获取失败
     * @pramas timeoutSec 锁持有的时间
     */
    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程标识  UUID用于区分不同JVM，ThreadId只能用于区分同一个JVM中的不同线程
        String threadId = ID_PREFIX + String.valueOf(Thread.currentThread().getId());
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        // 直接返回Bolean类型的success变量 会自动拆箱。有空指针安全风险
        return Boolean.TRUE.equals(success);
    }

    /**
     * 释放锁
     *
     * @return
     * @pramas
     */


    /*@Override
    public void unlock() {
        // 获取线程标识
         String threadId =ID_PREFIX + String.valueOf( Thread.currentThread().getId() );
        // 获取锁中的标识
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        // 判断标识是否一致
        if(threadId.equals(id)){
            stringRedisTemplate.delete(KEY_PREFIX+name);
        }
    }*/

    @Override
    public void unlock() {
        // 调用Lua脚本
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId()
        );
    }
}
