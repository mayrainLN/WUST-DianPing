package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
//import org.graalvm.compiler.lir.LIRInstruction;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    RedisIdWorker redisIdWorker;

    public static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    RedissonClient redissonClient;

    // 阻塞队列
    // private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);

    private IVoucherOrderService proxy;

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    // 异步的(单)线程池，用于持久化订单
    private ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
   /* private class VoucherOrderHandler implements Runnable{
        // 项目一启动，用户随时可能抢购,所有这个任务应该在初始化之后就应该执行
        @Override
        public void run() {
            while (true){
                try {
                    // 1.获取队列中的订单信息，获取不到就阻塞，不用担心占用cpu资源
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 用户下单已经完成，持久化订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("订单处理异常");
                    e.printStackTrace();
                }
                //
            }
        }
    }*/

    // 获取消息队列中的订单信息并持久化
    private class VoucherOrderHandler implements Runnable {
        // 项目一启动，用户随时可能抢购,所以这个任务应该在初始化之后就应该执行

        String queueName = "stream.orders";

        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息
                    // XREAD GROUP g1 c1 COUNT1 BLOCK 2000 STREAMS stream.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );

                    // 2. 判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 2.1 失败，说明没有消息，继续下一次存货
                        continue;
                    }

                    // 3. 成功，创建订单
                    // 3.1 取出订单
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);

                    // 完成业务 ,用户下单已经完成，持久化订单
                    handleVoucherOrder(voucherOrder);

                    // 3. ACK确认成功消费消息
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());


                } catch (Exception e) {
                    log.error("订单处理异常");
                    // 没有被ack缺认，消息还在pendinglist
                    handlePendingList();
                    e.printStackTrace();
                }
                //
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息
                    // XREAD GROUP g1 c1 COUNT1 BLOCK 2000 STREAMS stream.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );

                    // 2. 判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 2.1 获取失败，说明pendingList里没有消息，结束循环
                        break;
                    }

                    // 3. 成功，创建订单
                    // 3.1 取出订单
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);

                    // 完成业务 ,用户下单已经完成，持久化订单
                    handleVoucherOrder(voucherOrder);

                    // 3. ACK确认成功消费消息
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());


                } catch (Exception e) {
                    log.error("订单处理异常");
                    // 没有被ack缺认，消息还在pendinglist,不用继续递归，因为循环还会继续
                    // 休眠一会再去尝试
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException interruptedException) {
                        interruptedException.printStackTrace();
                    }
                    e.printStackTrace();
                }
                //
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //这里不再是由主线程去执行，所以用UserHolder（ThreadLocal）取不到。
        //所以需要传入整个订单对象去获取用户id

        // 这里不太可能出现并发安全问题，但是还是保留了原来的加锁逻辑作为兜底
        Long userId = voucherOrder.getUserId();

        // 获取redission提供的锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        // 尝试获取锁
        boolean hasGetLock = lock.tryLock();

        // 判断是否获取锁
        if (!hasGetLock) {
            // 获取锁失败 （基本不会发生，这里再判断只是为了兜底）
            log.error("不允许重复下单");
            return;
        }

        try {
//              proxy = (IVoucherOrderService) AopContext.currentProxy();
            // 代理对象是proxy管理的，所以就能进行事务了
            // 但是点进这个方法，发现AopContext还是通过ThreadLocal来获取代理对象，
            // 但是这个函数是由主线程的子线程去执行，所以获取不到。
            // 所以应该将事务对象提前获取

            proxy.createVoucherOrder(voucherOrder);
            return;
            // 这样，获取锁之后才会去创建事务，提交事务后才会释放锁，锁的粒度刚刚好。
            // 锁放在方法签名上，锁住了整个对象，粒度太大了
            // 锁放在方法里，太小了，锁已经释放了，事务还没提交
        } finally {
            lock.unlock();
        }
    }

    /*public Result secKillVoucher(Long voucherId) {
        // 1. 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2. 判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 未开始
            return Result.fail("秒杀尚未开始");
        }
        // 3. 判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 未开始
            return Result.fail("秒杀已经结束");
        }
        // 4. 判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }
        // 5. 一人一单逻辑=
        // 5.1 用户id
        Long userId = UserHolder.getUser().getId();

        // 获取我们自己写的锁对象
//        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);

        // 获取redission提供的锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        // 尝试获取锁
        boolean hasGetLock = lock.tryLock();


        // 判断是否获取锁
        if (!hasGetLock) {
            // 获取锁失败
            return Result.fail("不允许重复下单");
        }

        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            // 代理对象是proxy管理的，所以就能进行事务了
            return proxy.createVoucherOrder(voucherId);
            // 这样，获取锁之后才会去创建事务，提交事务后才会释放锁，锁的粒度刚刚好。
            // 锁放在方法签名上，锁住了整个对象，粒度太大了
            // 锁放在方法里，太小了，锁已经释放了，事务还没提交
        } finally {
            lock.unlock();
        }
    }*/

  /*  public Result secKillVoucher(Long voucherId) {
        // 获取用户
        Long userId = UserHolder.getUser().getId();
        // 获取订单id
        Long orderId = redisIdWorker.nextId("order");
        // 1. 执行lua脚本

//        --- 1.1 参数列表 优惠券（商品）id
//        local voucherId = ARGV[1]
//                --- 1.2 参数列表 用户id
//        local userId = ARGV[2]
//                --- 1.3 参数列表 订单id
//        local orderId = ARGV[3]

        Long result = stringRedisTemplate.execute(
                // 脚本
                SECKILL_SCRIPT,
                // 返回结果
                Collections.emptyList(),
                // 参数
                voucherId.toString(),
                userId.toString(),
               String.valueOf(orderId)
        );
        // 2. 判断下单结果
        int r = result.intValue();
        // 2.1 没有购买资格
        if (r!= 0){
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }
        // 2.2 有购买资格

        // 将订单放入阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        // 3.1.订单id
        voucherOrder.setId(orderId);
        // 3.2.用户id
        voucherOrder.setUserId(userId);
        // 3.3.代金券id
        voucherOrder.setVoucherId(voucherId);
        // 3.4 放入阻塞队列
        orderTasks.add(voucherOrder);
        // 初始化代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        return Result.ok(orderId);


        // 3. 返回订单id
    }*/

    public Result secKillVoucher(Long voucherId) {
        // 获取用户
        Long userId = UserHolder.getUser().getId();
        // 获取订单id
        Long orderId = redisIdWorker.nextId("order");
        // 1. 执行lua脚本
        // 脚本任务：判断购买资格、发送订单消息到消息队列
        Long result = stringRedisTemplate.execute(
                // 脚本
                SECKILL_SCRIPT,
                // 返回结果
                Collections.emptyList(),
                // 参数
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );
        // 2. 判断下单结果
        int r = result.intValue();
        // 2.1 没有购买资格
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 2.2 有购买资格

        // 脚本返回0之后，代表订单已经进入消息队列 阻塞队列这段代码就不需要了
       /* // 将订单放入阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        // 3.1.订单id
        voucherOrder.setId(orderId);
        // 3.2.用户id
        voucherOrder.setUserId(userId);
        // 3.3.代金券id
        voucherOrder.setVoucherId(voucherId);
        // 3.4 放入阻塞队列
        orderTasks.add(voucherOrder);*/
        // 初始化代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        return Result.ok(orderId);


        // 3. 返回订单id
    }

    // 更改后的创建订单过程是主线程丢给子线程异步执行的，订单已经创建完成并从redis把订单信息返回给前端了
    // 所以现在不再需要给前端任何返回信息了，所以方法返回值改为void就行
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 子线程不能通过ThreadLocal获取userId
        // Long userId = UserHolder.getUser().getId();
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        // 5.1.查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            log.error("用户已经购买过一次！");
            return;
        }

        // 6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
                .update();
        if (!success) {
            // 扣减失败
            log.error("库存不足！");
            return;
        }

        /*
        异步创建订单，所以这段不再需要了，订单id已经在redis里完成了
        // 7.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 7.1.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 7.2.用户id
        voucherOrder.setUserId(userId);
        // 7.3.代金券id
        voucherOrder.setVoucherId(voucherId);*/
        save(voucherOrder);

        // 7.返回订单id
//        return Result.ok(orderId);
    }
}
