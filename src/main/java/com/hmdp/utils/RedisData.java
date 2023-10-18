package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData <T> {
    private LocalDateTime expireTime;
    private T data; // 减少了在运行时进行类型转换的需要
    // private Object data; 如果你确实需要存储不同类型的数据
}
