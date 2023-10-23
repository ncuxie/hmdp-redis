package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
class HmDianPingApplicationTests {

    @Autowired
    ShopServiceImpl shopService;
    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Test
    public void testHyperLogLog() {
        // info memory 1509576
        String[] values = new String[1000];
        int j = 0;
        for (int i = 1; i <= 1000000; i++) {
            values[j++] = "user_" + i;
            if (i % 1000 == 0) {
                j=0;
                stringRedisTemplate.opsForHyperLogLog().add("hll", values);
            }
        }
        System.out.println("size: "+ stringRedisTemplate.opsForHyperLogLog().size("hll"));
    }

    /**
     * description unit testing for cache warm up
     */
    @Test
    public void saveShop2Redis() {
        shopService.saveShop2Redis(1L, 10L);
    }
}
