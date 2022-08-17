package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    public IUserService userService;

    @Resource
    StringRedisTemplate stringRedisTemplate;

    private void queryBlogUser(Blog blog) {
        // 查询发blog的用户  因为查询到的数据库中的blog里面只有发布者的id，但是最终返回的blog实体还需要发布者的头像和昵称
        Long userid = blog.getUserId();
        User user = userService.getById(userid);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }


    @Override
    public Result queryBlogById(Long id) {
        // 查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        queryBlogUser(blog);
        queryBlogIsLikedByUser(blog);
        return Result.ok(blog);
    }

    private void queryBlogIsLikedByUser(Blog blog) {
        // 1. 获取登陆用户
        UserDTO user = UserHolder.getUser();
        if (user==null){
            // 尚未登录，无需查询该用户是否给该博客点赞
            return;
        }
        Long userId = user.getId();
        // 2. 判断当前用户是否已经点赞
        String key = "blog:liked:" + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score != null) {
            blog.setIsLike(true);
        }
    }


    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = this.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            queryBlogUser(blog);
            queryBlogIsLikedByUser(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        // 1. 获取登陆用户
        Long userId = UserHolder.getUser().getId();
        // 2. 判断当前用户是否已经点赞
        String key = "blog:liked:" + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        // 3. 如果未点赞，可以点赞
        if (score == null) {
            // 3.1 数据库点赞数+1
            boolean isSuccess = update().setSql("liked = liked+1").eq("id", id).update();
            // 3.2 保存用户到redis的Zset集合
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        }
        // 4. 已经点赞，取消点赞
        else {
            // 4.1 数据库点赞数-1
            boolean isSuccess = update().setSql("liked = liked-1").eq("id", id).update();
            if (isSuccess) {
                // 4/2 把用户从redis的set集合移除
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }


        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        // 查询top5点赞的用户  zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5==null||top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        // 解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        // 查询用户信息
        /*List<UserDTO> userDTOS = userService.listByIds(ids).stream().
                map(
                        user -> BeanUtil.copyProperties(user, UserDTO.class)
                )
                .collect(Collectors.toList());*/

        //userService.listByIds(ids)
        // 是使用的SELECT id,phone,password,nick_name,icon,create_time,update_time FROM tb_user WHERE id IN ( ? )
        // 查询，sql执行后，查询结果并不能和给出的set集合顺序保持一致。
        // 所以这里要使用自定义查询
        String idStr = StrUtil.join(",", ids);
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("order by field(id," + idStr + ")").list()
                .stream().
                map(
                        user -> BeanUtil.copyProperties(user, UserDTO.class)
                )
                .collect(Collectors.toList());
        // 返回
        // 返回
        return Result.ok(userDTOS);
    }
}
