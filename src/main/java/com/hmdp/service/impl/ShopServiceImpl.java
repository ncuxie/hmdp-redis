package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.entity.Message.SHOP_NOT_EXISTS;
import static com.hmdp.utils.RedisConstants.*;

/**
 * @author 666
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * description 查询，逻辑过期方式
     *
     * @return Result-Shop
     */
    @Override
    public Shop queryWithLogicalExpire(Long id) {
        // 尝试从 redis 获取
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // redis 中店铺不存在
        if (StrUtil.isBlank(shopJson))
            return null;

        // redis 中查询到shop数据
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 判断逻辑过期
        if (LocalDateTime.now().isBefore(expireTime)) {
            // 未逻辑过期
            return shop;
        }

        String lock_key = LOCK_SHOP_KEY + id;
        Boolean isLocked = tryLock(lock_key);

        // 已逻辑过期,尝试获取互斥锁
        if (isLocked) {
            // 开启独立线程，完成逻辑更新
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lock_key);
                }
            });
        }
        // 此处返回的仍是逻辑过期的 shop 信息
        return shop;
    }

    @Override
    public Result queryById(Long id) {
        String key = CACHE_SHOP_KEY + id;

        Shop shop = queryWithLogicalExpire(id);
//        // 查询数据库之前先查询缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//
//        // 如果缓存数据存在，则直接从缓存中返回
//        if (StrUtil.isNotBlank(shopJson)) {
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return Result.ok(shop);
//        }
//
//        // 如果缓存数据不存在，再查询数据库，然后将数据存入redis
//        Shop shop = this.getById(id);
        if (shop == null) {
            // 添加 null 值,防止缓存穿透(缓存穿透：key在redis和数据库中都不存在，导致请求直达数据库)
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail(SHOP_NOT_EXISTS);
        }
//
//        // 数据库中存在 shop，添加到缓存中
//        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        // 店铺不存在
        if (id == null)
            return Result.fail(SHOP_NOT_EXISTS);
        String key = CACHE_SHOP_KEY + id;
        // 先修改数据库
        this.updateById(shop);
        // 再删 redis 缓存
        stringRedisTemplate.delete(key);
        return Result.ok();
    }

    // 工具方法：将 shop 存储到 redis，并附加 expireSeconds 逻辑过期参数。
    public void saveShop2Redis(Long id, Long expireSeconds) {
        // 创建缓存对象
        Shop shop = this.getById(id);
        RedisData<Shop> redisData = new RedisData<>();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 缓存到 redis
        String key = CACHE_SHOP_KEY + id;
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    // 工具方法：互斥锁
    public Boolean tryLock(String key) {
        return stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10L, TimeUnit.SECONDS);
    }

    public void unLock(String key) {
        stringRedisTemplate.delete(key);
    }
}
