package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOPTYPE;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
// 响应时间从59ms 到 5.84ms
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Override
    public List<ShopType> getList() {
        // CACHE_SHOPTYPE = "cache:shopType";
        String key = CACHE_SHOPTYPE;
        // 查询缓存
        String typeListJson = stringRedisTemplate.opsForValue().get(key);

        if (typeListJson != null) {
            // 将查询到的JOSN字符串转化为数组返回
            return JSONUtil.toList(typeListJson,ShopType.class);
        }

        // cahe没有，查数据库
        List<ShopType> list = this.list();
        if (list == null){
            return new ArrayList<ShopType>();
        }
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(list));
        return list;
    }
}
