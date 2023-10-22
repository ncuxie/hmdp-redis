package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    // TODO: 思考？ 接口是什么？ 表的结构如何？ 存到 redis 还是 mysql？

    @GetMapping("/or/not/{id}")
    public Result isFollowed(@PathVariable(name = "id") Long followUserId){
        return followService.isFollowed(followUserId);
    }

    @PutMapping("/{id}/{toFollow}")
    public Result follow(@PathVariable(name = "id") Long followUserId,
                         @PathVariable(name = "toFollow") Boolean isFollowed){
        return followService.follow(followUserId,isFollowed);
    }

    @GetMapping("/common/{id}")
    public Result followCommons(@PathVariable Long id){
        return followService.followCommons(id);
    }

}