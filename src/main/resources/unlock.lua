---
--- Generated by EmmyLua(https://github.com/EmmyLua)
--- Created by 刘楠.
--- DateTime: 2022/8/13 20:02
---

--- 比锁
if (redis.call('get', KEYS[1]) == ARGV[1]) then
    --- 删锁
    return redis.call('del', KEYS[1])
end
return 0