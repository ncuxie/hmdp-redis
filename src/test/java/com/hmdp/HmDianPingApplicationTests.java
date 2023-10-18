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
     * description ��Ԫ���Խ��л���Ԥ��
     */
    @Test
    public void saveShop2Redis(){
        shopService.saveShop2Redis(1L, 10L);
    }
}
