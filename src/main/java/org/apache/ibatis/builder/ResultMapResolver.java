/**
 * Copyright 2009-2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.builder;

import java.util.List;

import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;

/**
 * @author Eduardo Macarron
 */
public class ResultMapResolver {

    /**
     * 构建 Mapper 相关元数据的工具类
     */
    private final MapperBuilderAssistant assistant;

    /**
     * resultMap 的标识
     */
    private final String id;

    /**
     * resultMap 标识的 Java 类型
     */
    private final Class<?> type;

    /**
     * /mapper/resultMap 节点 extends 属性，用于 discriminator
     */
    private final String extend;

    /**
     * 鉴别器
     */
    private final Discriminator discriminator;

    /**
     * 普通的列映射关系
     */
    private final List<ResultMapping> resultMappings;

    /**
     * 是否开启自动映射
     */
    private final Boolean autoMapping;

    public ResultMapResolver(MapperBuilderAssistant assistant, String id, Class<?> type, String extend, Discriminator discriminator, List<ResultMapping> resultMappings, Boolean autoMapping) {
        this.assistant = assistant;
        this.id = id;
        this.type = type;
        this.extend = extend;
        this.discriminator = discriminator;
        this.resultMappings = resultMappings;
        this.autoMapping = autoMapping;
    }

    public ResultMap resolve() {
        return assistant.addResultMap(this.id, this.type, this.extend, this.discriminator, this.resultMappings, this.autoMapping);
    }

}
