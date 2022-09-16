package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.AliCloudMS;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
//此实现类继承了mybatisPLUS的一个泛型类类，这个泛型类实现了IService接口，里面有很多查询功能
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    StringRedisTemplate stringRedisTemplate;

//    @Override
//    public Result sendCode(String phone, HttpSession session) {
////        1.校验手机号
//        if (RegexUtils.isPhoneInvalid(phone)){
////        2.如果不符合，返回错误信息
//            return Result.fail("手机号格式错误");
//        }
////        3.符合生成验证码
//        String code = RandomUtil.randomNumbers(6);
////        4.保存验证码到session
//        session.setAttribute("code",code);
////        5.发送验证码
//        log.debug("发送短信验证码成功，验证码：{}",code);
//        return Result.ok();
//    }

    @Override
    public Result sendCode(String phone, HttpSession session) {
//        1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
//        2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }
//        3.符合生成验证码
        String code = RandomUtil.randomNumbers(6);
//        4.保存验证码到redis
//        key->login:code:199999  val->898989
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code);
//        5.发送验证码
        try {
//            AliCloudMS.sendMS(phone,code);
            log.debug("发送短信验证码成功，验证码：{}", code);
        } catch (Exception e) {
            log.error("阿里云MS服务异常");
            log.info("code:"+code);
            e.printStackTrace();
        }
        return Result.ok();
    }

//    @Override
//    public Result login(LoginFormDTO loginForm, HttpSession session) {
//        //1.校验手机号
//        String phone = loginForm.getPhone();
//        if(RegexUtils.isPhoneInvalid(phone)){
//            //2.手机号格式错误
//            return Result.fail("手机号格式错误！");
//        }
//        //3. 校验验证码  签发验证码的时候缓存就已经存入session 将session中的验证码和DTO传送来的session来对比
//        //每个不同的浏览器发起请求的时候都有个独立的session tomcat内部维护了很多不同的session 。所以都用code作为key也是不干扰的。
//        //但是在redis上就不一样了，还这样存，所有的用户的session信息都在一个位置上，覆盖来覆盖去。永远只有一个实体
//        Object cacheCode = session.getAttribute("code");
//        String  code= loginForm.getCode();
//        if(cacheCode==null||!cacheCode.toString().equals(code)){
//            //验证不一致
//            return Result.fail("验证码错误");
//        }
//
//        //登录、注册逻辑  mybatisPLUS的功劳  IService里面有query方法 帮我们单表查询
//        User user = query().eq("phone", phone).one();
//
//        if(user==null){
//            //创建新用户
//            user=createUserWithPhone(phone);
//        }
//
//        //保存用户信息到session中。 每个session都有一个唯一的sessionid，在访问tomcat的时候，sessionid就已经自动的写入返回头中的cookies字段了
//        //所以不需要额外返回登陆凭证，其实登陆凭证被自动化保存在了返回头中了
//        //将整个实体类返回给前端不安全，用工具类转换成数据传输对象即可
//        session.setAttribute("user", BeanUtil.copyProperties(user,UserDTO.class));
//        return Result.ok();
//    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.手机号格式错误
            return Result.fail("手机号格式错误！");
        }

//      校验验证码

        String code = loginForm.getCode();
//        在redis中格式化字符串前缀，方便查找；LOGIN_CODE_KEY = "login:code:";
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (cacheCode == null || !cacheCode.equals(code)) {
            //验证不一致
            return Result.fail("验证码错误");
        }

        //登录、注册逻辑  mybatisPLUS的功劳  IService里面有query方法 帮我们单表查询
        User user = query().eq("phone", phone).one();

        if (user == null) {
            //没有找到，是新用户，创建新用户
            user = createUserWithPhone(phone);
        }

//        成功取出用户信息
        //转化成DTO，序列号到map中。
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor(
                        (k, v) -> v.toString()
                ));

        //存储到redis中
        //随机生成token，作为登录令牌,键名前面拼接，便于查找
        String token = UUID.fastUUID().toString(true);
//      LOGIN_USER_KEY -> login:token:
        String tokenKey = LOGIN_USER_KEY + token;
//        将该用户的token存入redis
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
//      设置token过期时间
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        return Result.ok(tokenKey);
    }

    private User createUserWithPhone(String phone) {
        //创建新用户
        User user = new User();
        user.setPhone(phone);
        //将随机名称加个规范前缀
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        //mybatisPLUS保存用户
        save(user);
        return user;
    }
}
