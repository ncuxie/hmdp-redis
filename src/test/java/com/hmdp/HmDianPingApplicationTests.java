package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class HmDianPingApplicationTests {

    @Autowired
    ShopServiceImpl shopService;

    /**
     * description 单元测试进行缓存预热
     */
    @Test
    public void saveShop2Redis(){
        shopService.saveShop2Redis(1L, 10L);
    }
}
