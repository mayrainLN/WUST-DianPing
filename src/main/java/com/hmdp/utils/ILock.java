package com.hmdp.utils;

/**
 * @author :MayRain
 * @version :1.0
 * @date :2022/8/13 17:02
 * @description :
 */


public interface ILock {
    /**
     * 尝试获取锁
     * @pramas timeoutSec 锁持有的时间
     * @return true 获取成功   false 获取失败
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     * @pramas
     * @return
     */
    void unlock();
}
