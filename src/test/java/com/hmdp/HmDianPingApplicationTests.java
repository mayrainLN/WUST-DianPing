package com.hmdp;

import com.hmdp.entity.User;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisIdWorker redisIdWorker;

    private ExecutorService es =  Executors.newFixedThreadPool(500);

    @Test
    void testSaveShop(){
        try {
            shopService.saveShop2redis(1L,10L);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }


    @Test
    void testIdWorker() throws InterruptedException {
        // 之后再去补习JUC
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = ()->{
            for (int i = 0 ; i<100; i++){
                long id = redisIdWorker.nextId("order");
                System.out.println("id = "+id);
            }
            latch.countDown();
        };

        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = "+(end - begin));
    }

    @Test
    void testA(){

//        System.out.println(a.getClass().toString());
    }

}
