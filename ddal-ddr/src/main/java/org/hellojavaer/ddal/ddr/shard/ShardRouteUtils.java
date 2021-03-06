/*
 * Copyright 2017-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hellojavaer.ddal.ddr.shard;

import java.util.*;

/**
 *
 * @author <a href="mailto:hellojavaer@gmail.com">Kaiming Zou</a>,created on 20/06/2017.
 */
public class ShardRouteUtils {

    public static Map<ShardRouteInfo, List<RangeShardValue>> groupSdValuesByRouteInfo(ShardRouter shardRouter,
                                                                                      String scName, String tbName,
                                                                                      RangeShardValue sdValues) {
        ShardRouteConfig routeConfig = shardRouter.getRouteConfig(scName, tbName);
        ShardRouteRule rule = shardRouter.getRouteRule(routeConfig.getScName(), routeConfig.getTbName());
        Map<ShardRouteInfo, List<RangeShardValue>> map = rule.groupSdValuesByRouteInfo(scName, tbName, sdValues);
        if (map == null) {
            return Collections.emptyMap();
        }
        return map;
    }

    public static <T> Map<ShardRouteInfo, List<T>> groupSdValuesByRouteInfo(ShardRouter shardRouter, String scName,
                                                                            String tbName, List<T> sdValues) {
        if (sdValues == null || sdValues.isEmpty()) {
            return Collections.EMPTY_MAP;
        }
        Map<ShardRouteInfo, List<T>> map = new LinkedHashMap<>();
        for (Object item : sdValues) {
            ShardRouteInfo routeInfo = shardRouter.getRouteInfo(scName, tbName, item);
            List list = map.get(routeInfo);
            if (list == null) {
                list = new ArrayList();
                map.put(routeInfo, list);
            }
            list.add(item);
        }
        return map;
    }

    public static <T> Map<ShardRouteInfo, Set<T>> groupSdValuesByRouteInfo(ShardRouter shardRouter, String scName,
                                                                           String tbName, Set<T> sdValues) {
        if (sdValues == null || sdValues.isEmpty()) {
            return Collections.EMPTY_MAP;
        }
        Map<ShardRouteInfo, Set<T>> map = null;
        if (sdValues instanceof LinkedHashSet) {
            map = new LinkedHashMap();
        } else {
            map = new HashMap<>();
        }
        for (Object item : sdValues) {
            ShardRouteInfo routeInfo = shardRouter.getRouteInfo(scName, tbName, item);
            Set set = map.get(routeInfo);
            if (set == null) {
                set = new LinkedHashSet();
                map.put(routeInfo, set);
            }
            set.add(item);
        }
        return map;
    }

    public static Map<String, List<ShardRouteInfo>> groupRouteInfosByScName(List<ShardRouteInfo> routeInfos) {
        if (routeInfos == null || routeInfos.isEmpty()) {
            return Collections.EMPTY_MAP;
        }
        Map<String, List<ShardRouteInfo>> map = new LinkedHashMap<>();
        for (ShardRouteInfo routeInfo : routeInfos) {
            List<ShardRouteInfo> list = map.get(routeInfo.getScName());
            if (list == null) {
                list = new ArrayList<>();
                map.put(routeInfo.getScName(), list);
            }
            list.add(routeInfo);
        }
        return map;
    }

    public static Map<String, Set<ShardRouteInfo>> groupRouteInfosByScName(Set<ShardRouteInfo> routeInfos) {
        if (routeInfos == null || routeInfos.isEmpty()) {
            return Collections.EMPTY_MAP;
        }
        Map<String, Set<ShardRouteInfo>> map = null;
        if (routeInfos instanceof LinkedHashSet) {
            map = new LinkedHashMap();
        } else {
            map = new HashMap<>();
        }
        for (ShardRouteInfo routeInfo : routeInfos) {
            Set<ShardRouteInfo> set = map.get(routeInfo.getScName());
            if (set == null) {
                set = new LinkedHashSet<>();
                map.put(routeInfo.getScName(), set);
            }
            set.add(routeInfo);
        }
        return map;
    }

}
