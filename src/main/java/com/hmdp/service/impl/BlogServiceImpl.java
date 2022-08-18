package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
//import com.sun.org.apache.bcel.internal.generic.RETURN;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

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
    private IUserService userService;

    @Resource
    private IFollowService followService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

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

    @Override
    public Result saveBlog(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2. 保存探店博文
        boolean isSuccess = this.save(blog);
        if(!isSuccess){
            return Result.fail("新增笔记失败！");
        }
        // 3. 发送消息到粉丝的信箱
        // 3.1 查询作者的所有粉丝
        // select * from tb_follow where follow_user_id = ?
        List<Follow> follows = followService.query().eq("follow_user_id",user.getId()).list();
        // 4.1 推送笔记到所有粉丝
        // 查询得到的是关注关系表里的数据，只存了两个关联主键
        // 保持该时间戳在所有收件箱都一致,否则各个粉丝收件箱里的博客时间戳都不一样
        Long time=System.currentTimeMillis();
        for (Follow follow:follows){
            // 获取粉丝id
            Long userId = follow.getUserId();
            // 将该篇博文推送到所有粉丝的信箱中
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),
                    // 以笔记的发布时间为score
                    time
            );
            // 5. 返回笔记id
            return Result.ok(blog.getId());
        }

        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2. 查询用户的信箱  ZREVRANGEBYSCORE key Max Min LIMIT offset count
        //                                               起始   修正   页大小
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);

        // 3.非空判断
        if (typedTuples==null||typedTuples.isEmpty()){
            return Result.ok();
        }
        // 4. 解析数据：blogId，score（最小时间戳），offset
        List<Long> ids = new ArrayList<>(typedTuples.size()); // 直接初始化足够的容量，防止后续扩容消耗性能
        long minTime = 0; //当前找到的最小时间
        int os = 0;
        for (ZSetOperations.TypedTuple<String> typedTuple:typedTuples){
            // 4.1 获取id
            String id = typedTuple.getValue();
            ids.add(Long.valueOf(typedTuple.getValue()));
            // 4.2 获取分数（时间戳）
            Long time = typedTuple.getScore().longValue();
            if(time == minTime){ // 又发现了最新时间，offset自增
                os++;
            }else{
                minTime = time;//更新最小时间(zset里是按顺序存放的)
                os=1; // 重置offset
            }

        }
        String idStr = StrUtil.join(",", ids);
        // 5. 根据id查询blog，放入 set  不要listById 他是用sql里的in查询，会破坏顺序
        List<Blog> blogs = query().in("id",ids).last("order by field(id,"+idStr+")").list();

        // 6. 封装并返回
        // 6.1. 注入博客的非库表信息
        for (Blog blog:blogs){
            queryBlogUser(blog);
            queryBlogIsLikedByUser(blog);
        }
        ScrollResult r = new ScrollResult().setList(blogs).setOffset(os).setMinTime(minTime);
        return Result.ok(r);
    }
}
