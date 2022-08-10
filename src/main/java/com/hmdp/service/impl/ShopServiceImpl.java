package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author MayRain
 * @since 2021-12-22
 */
//IShopService继承mabatisPULS提供的IService接口
//将要查询的类型泛型参数作为传入IService。IService的实现类ServiceImpl就可以帮我们自动做单表查询
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        // 解决缓存穿透   // lamda可以换成 this::getById
         Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class,id2 ->{
             return getById(id2);
         },CACHE_SHOP_TTL,TimeUnit.MINUTES);

        // 解决缓存穿透基础上，基于互斥锁解决缓存击穿
//        Shop shop = cacheClient.queryWithLogicalExpire(
//                CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.SECONDS
//        );

        // 基于逻辑过期解决缓存击穿
        // 解决缓存穿透   // lamda可以换成 this::getById
//        Shop shop = cacheClient.queryWithMutex(CACHE_SHOP_KEY,id,Shop.class,id2 ->{
//            return getById(id2);
//        },CACHE_SHOP_TTL,TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }



    // 将商铺数据存入Redis的方法 设置逻辑过期时间
    public void saveShop2redis(Long id, long expireSeconds) throws InterruptedException {
        // 1. 查询商铺数据
        Shop shop = this.getById(id);
        //模拟缓存重建的延迟(业务复杂，多个服务调用的时候会有延迟)
        Thread.sleep(200);

        // 2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3. 写入redis
        // 没有给该记录添加ttl，可以认为是永久有效。具体过期处理在代码逻辑上进行处理
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }



    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

/*    //通过逻辑过期解决缓存击穿问题的 店铺查询
    public Shop queryWithLogicalExpire(Long id) {
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
        Shop shop = JSONUtil.toBean(jsonObject, Shop.class);
        // 判断是否过期
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            // 过期时间在现在之后，即未过期
            // 直接返回
            return shop;
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
                    this.saveShop2redis(id, 20L);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 失败，直接返回过期的缓存信息

        return shop;
    }

    //解决了缓存穿透问题的 店铺查询
    public Shop queryWithPassThrough(Long id) {
        // 加入前缀，分门别类
        String key = CACHE_SHOP_KEY + id;

        // 先从rerdis查询
        String shopJSON = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJSON)) {
            // 查到了，直接返回
            Shop shop = JSONUtil.toBean(shopJSON, Shop.class);
            return shop;
        }
        // 缓存未命中，查询数据库
        Shop shop = this.getById(id);
        // 数据库也找不到
        if (shop == null) {
            // 解决缓存传统，将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        if (shopJSON != null) {
            // 说明缓存中的数据是空字符串
            // 返回一个错误信息
            return null;
        }

        //找到了，存入redis，设置TTL作为缓存不一致的兜底
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shop;
    }

        //通过互斥锁解决缓存击穿问题的 店铺查询
    public Shop queryWithMutex(Long id) {
        // 加入前缀，分门别类
        String key = CACHE_SHOP_KEY + id;

        // 先从rerdis查询
        String shopJSON = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJSON)) {
            // 查到了，直接返回
            Shop shop = JSONUtil.toBean(shopJSON, Shop.class);
            return shop;
        }
        // 缓存未命中，实现缓存重建
        // 1. 获取互斥锁
        //每个店铺对应一个锁
        String lockKey = "lock:shop:" + id;
        Shop shop = null;
        try {
            boolean hasGetLock = tryLock(lockKey);
            // 2. 失败-> 休眠后再重试
            if (!hasGetLock) {
                Thread.sleep(50);
                return this.queryWithMutex(id);
            }
            // 3. 成功-> 根据id查询数据库，再写回缓存

            shop = this.getById(id);

            // 模拟缓存重建的延迟
            Thread.sleep(200);

            // 数据库也找不到
            if (shop == null) {
                // 解决缓存传统，将空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            if (shopJSON != null) {
                // 说明缓存中的数据是空字符串
                // 返回一个错误信息
                return null;
            }

            //找到了，存入redis，设置TTL作为缓存不一致的兜底
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            // 4. 写回缓存后释放锁
            this.unlock(lockKey);
        }
        return shop;
    }
    */

    @Override
    @Transactional //加上注解异常自动回滚
    //在@Transactional注解中如果不配置rollbackFor属性
    // 那么事物只会在遇到RuntimeException的时候才会回滚,
    // 加上rollbackFor=Exception.class,可以让事物在遇到非运行时异常时也回滚
    public Result update(Shop shop) {
        if (shop.getId() == null) {
            return Result.fail("店铺id不能为空");
        }
        // 先更新数据库
        this.updateById(shop);
        // 再删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }
}
