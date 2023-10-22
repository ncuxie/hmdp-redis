package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
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

    @Override
    public Result queryMyBlog(Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = this.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
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
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("Blog not exists!");
        }
        isBlogLiked(blog);
        queryBlogUser(blog);
        return Result.ok(blog);
    }

    @Override
    public Result likeBlog(Long blog_id) {
        Long user_id = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY + blog_id;
        Double score = stringRedisTemplate.opsForZSet().score(key, user_id.toString());

        // 用户未点赞
        if (score == null) {
            // 修改点赞数量
            boolean succeed = this.update().setSql("liked = liked + 1").eq("id", blog_id).update();
            if (succeed) {
                stringRedisTemplate.opsForZSet().add(key, user_id.toString(), System.currentTimeMillis());
            }
        } else {
            // 用户已点赞
            boolean succeed = this.update().setSql("liked = liked - 1").eq("id", blog_id).update();
            if (succeed) {
                stringRedisTemplate.opsForZSet().remove(key, user_id.toString());
            }
        }
        return Result.ok();
    }

    /**
     * top 5 likes of the blog
     *
     * @param blog_id 博客id
     * @return Result
     */
    @Override
    public Result queryBlogLikes(Long blog_id) {
        // TODO: TO BE REVIEWED. queryBlogLikes stream
        // list 0-4 user likes
        String key = BLOG_LIKED_KEY + blog_id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);

        // user_list empty
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        // 2.解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);

        // 3.根据用户id查询用户
        List<UserDTO> userDTOs = userService.query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")")
                .list().stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOs);
    }

    /**
     * 保存博主新发布的blog，并以 feed push 模式推送到关注者
     *
     * @param blog new blog
     * @return Result
     */
    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        Long user_id = UserHolder.getUser().getId();
        blog.setUserId(user_id);
        // 保存探店博文
        boolean saved = this.save(blog);
        // 保存 blog 失败
        if (!saved) {
            return Result.fail("发布失败！请稍后重试！");
        }

        // Save successfully, push to followers (redis-push->follower)
        // Search the database and get followers id
        List<Follow> follows = followService.query().select("user_id").eq("follow_user_id", user_id).list();

        // Push blog to followers (push -> redis)
        for (Follow follow : follows) {
            // get followers id
            Long follower_id = follow.getUserId();
            // push -> redis
            String key = FEED_KEY + follower_id;
            // score(timestamp) to rank
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        return Result.ok(blog.getId());
    }

    /**
     * 安照时间戳顺序，分页返回关注的博主发布的博客
     * According to the timestamp order, return to the blogs published by the bloggers you follow in paging.
     * TODO: test it
     * @param max    上次最后时间戳
     * @param offset 上次相同时间戳数量
     * @return Result
     */
    @Override
    public Result queryBlogOfFollowed(Long max, Integer offset) {
        Long userId = UserHolder.getUser().getId();
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(FEED_KEY + userId, 0, max, offset, 3);
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }

        // 4.解析数据：blogId、minTime（时间戳）、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0; // 2
        int next_offset = 1; // 2
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) { // 5 4 4 2 2
            // 4.1.获取id
            ids.add(Long.valueOf(Objects.requireNonNull(tuple.getValue())));
            // 4.2.获取分数(时间戳）
            long time = Objects.requireNonNull(tuple.getScore()).longValue();
            if (time == minTime) {
                next_offset++;
            } else {
                minTime = time; // 更新最小时间戳
                next_offset = 1;
            }
        }
        next_offset = (minTime == max) ? (next_offset) : (next_offset + offset);
        // 5.根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Blog blog : blogs) {
            // 5.1.查询blog有关的用户
            queryBlogUser(blog);
            // 5.2.查询blog是否被点赞
            isBlogLiked(blog);
        }
        ScrollResult result = ScrollResult.builder()
                .minTime(minTime)
                .offset(next_offset)
                .list(blogs)
                .build();
        return Result.ok(result);
    }

    /**
     * 判断用户是否点赞
     *
     * @param blog bk
     */
    private void isBlogLiked(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户未登录，无需查询是否点赞
            return;
        }
        Long user_id = user.getId();
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, user_id.toString());
        blog.setIsLike(score != null);
    }

    /**
     * 根据笔记查询作者信息并添加到blog
     *
     * @param blog blog信息
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
