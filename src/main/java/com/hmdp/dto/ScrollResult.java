package com.hmdp.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

// 滚动分页的查询结果
@Data
@Accessors(chain = true)
public class ScrollResult {
    private List<?> list;// 上界为Object
    private Long minTime;
    private Integer offset;
}
