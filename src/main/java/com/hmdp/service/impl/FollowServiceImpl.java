package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result isFollowed(Long followUserId) {
        Long user_id = UserHolder.getUser().getId();
        Long count = this.query()
                .eq("user_id", user_id)
                .eq("follow_user_id", followUserId)
                .count();
        return Result.ok(count>0);
    }

    @Override
    public Result follow(Long followUserId, Boolean toFollow) {
        Long user_id = UserHolder.getUser().getId();
        // 关注
        if (toFollow){
            Follow follow = Follow.builder()
                    .userId(user_id)
                    .followUserId(followUserId)
                    .createTime(LocalDateTime.now())
                    .build();
            // 存储到 mysql 数据库
            boolean saved = this.save(follow);
            // 添加成功，加入 redis 中
            if (saved){
                stringRedisTemplate.opsForSet().add("follows:"+user_id,followUserId.toString());
                // stringRedisTemplate.expire("follows:"+user_id, 30L, TimeUnit.MINUTES);
            }
        } else {
            // 已关注 -> 取消关注，从数据库删除 (delete from tb_follow where user_id = ? and follow_user_id = ?)
            boolean removed = this.remove(new QueryWrapper<Follow>()
                    .eq("user_id", user_id)
                    .eq("follow_user_id", followUserId));
            // 删除成功，从 redis 中移除
            if (removed){
                stringRedisTemplate.opsForSet().remove("follows:"+user_id,followUserId.toString());
            }
        }
        return Result.ok();
    }

    /**
     *
     * @param id 博主id
     * @return Result List<UserDTO>
     */
    @Override
    public Result followCommons(Long id) {
        // 1.获取当前用户id
        Long user_id = UserHolder.getUser().getId();
        // 2.查询当前 user 的 redis 关注列表
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect("follows:" + user_id, "follows:" + id);
        // TODO: redis中不存在，是否需要去查询数据库（redis关注列表的缓存过期时间？）
        // 3.1无共同关注
        if (intersect == null || intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 4.解析id集合
        List<Long> ids = intersect.stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());
        // 5.查询用户
        List<UserDTO> userDTOList = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOList);
    }
}
