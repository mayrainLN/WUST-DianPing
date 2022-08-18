package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
//@Service是把spring容器中的bean进行实例化，也就是等同于new操作，只有实现类是可以进行new实例化的，而接口则不能，所以是加在实现类上的。
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    IUserService userService;

    // 关注、取关某个用户
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 获取发出当前操作的用户的用户信息
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        // 判断是关注还是取关
        if (isFollow) {
            // 关注，新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if (isSuccess) {
                // 把被关注的用户id，放入redis的set集合
                // sadd userId followUserId  当前用户 的关注列表（集合）
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            // 取关，从关系表中删除
            remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
            stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
        }
        return Result.ok();
    }

    // 判断是否关注了某个用户
    @Override
    public Result isFollow(Long followUserId) {
        // 获取发出当前操作的用户的用户信息
        Long userId = UserHolder.getUser().getId();
        // 查询是否关注
//        user_id   follow_user_id
//        001        002
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count > 0);
    }

    // 共同关注列表
    @Override
    public Result followCommons(Long id) {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 当前用户的关注列表的key
        String key = "follows:" + userId;
        // 要查询的用户的关注列表的key
        String key2 = "follows:" + id;
        Set<String> interSet = stringRedisTemplate.opsForSet().intersect(key, key2);
        if (interSet == null || interSet.isEmpty()) {
            // 无交集
            return Result.ok(Collections.emptyList());
        }

        //解析id集合
        List<Long> ids=  interSet.stream().map(Long::valueOf).collect(Collectors.toList());

        // 根据id集合，查询共同关注列表
        List<UserDTO> userDTOS = userService.listByIds(ids)
                .stream()
                .map(user -> {
                    return BeanUtil.copyProperties(user,UserDTO.class);
                })
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
