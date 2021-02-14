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
package org.apache.ibatis.builder.xml;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * include 节点处理
 *
 * @author Frank D. Martinez [mnesarco]
 */
public class XMLIncludeTransformer {

    /**
     * 配置
     */
    private final Configuration configuration;

    /**
     * mapper 元数据构建工具
     */
    private final MapperBuilderAssistant builderAssistant;

    public XMLIncludeTransformer(Configuration configuration, MapperBuilderAssistant builderAssistant) {
        this.configuration = configuration;
        this.builderAssistant = builderAssistant;
    }

    /**
     * 处理 /mapper/select|insert|update|delete 节点中的 include 节点
     *
     * @param source /mapper/select|insert|update|delete 节点
     */
    public void applyIncludes(Node source) {
        Properties variablesContext = new Properties();
        Properties configurationVariables = configuration.getVariables();
        Optional.ofNullable(configurationVariables).ifPresent(variablesContext::putAll);
        applyIncludes(source, variablesContext, false);
    }

    /**
     * 处理 /mapper/select|insert|update|delete 节点中的 include 节点：
     * 1. 文本内容替换占位符为变量值
     * 2. include 节点替换为 sql 内容
     * <p>
     * Recursively apply includes through all SQL fragments.
     *
     * @param source           /mapper/select|insert|update|delete 节点 或
     *                         /mapper/select|insert|update|delete 节点中的子元素 或
     *                         /mapper/sql 节点 或
     *                         /mapper/sql 节点中的文本内容
     *                         <p>
     *                         Include node in DOM tree
     * @param variablesContext Current context for static variables with values
     */
    private void applyIncludes(Node source, final Properties variablesContext, boolean included) {
        if ("include".equals(source.getNodeName())) { // include 节点
            // 临时 sql 节点
            Node toInclude = findSqlFragment(getStringAttribute(source, "refid"), variablesContext);
            // sql 节点中可用的变量
            Properties toIncludeContext = getVariablesContext(source, variablesContext);
            // 临时 sql 节点中的占位符替换为变量值
            applyIncludes(toInclude, toIncludeContext, true);
            if (toInclude.getOwnerDocument() != source.getOwnerDocument()) {
                toInclude = source.getOwnerDocument().importNode(toInclude, true);
            }
            // 将 include 节点替换为 sql 节点
            source.getParentNode().replaceChild(toInclude, source);
            while (toInclude.hasChildNodes()) {
                // sql 节点前插入 sql 节点的文本内容
                toInclude.getParentNode().insertBefore(toInclude.getFirstChild(), toInclude);
            }
            // 移除 sql 节点
            toInclude.getParentNode().removeChild(toInclude);
        } else if (source.getNodeType() == Node.ELEMENT_NODE) {
            // select|insert|update|delete 节点 included = false
            // sql 节点 included = true
            if (included && !variablesContext.isEmpty()) {
                // replace variables in attribute values
                NamedNodeMap attributes = source.getAttributes();
                for (int i = 0; i < attributes.getLength(); i++) {
                    Node attr = attributes.item(i);
                    //  设置 sql 节点属性中的占位符
                    attr.setNodeValue(PropertyParser.parse(attr.getNodeValue(), variablesContext));
                }
            }
            NodeList children = source.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                // 解析 select|insert|update|delete|sql 节点子元素
                applyIncludes(children.item(i), variablesContext, included);
            }
        } else if (included && (source.getNodeType() == Node.TEXT_NODE || source.getNodeType() == Node.CDATA_SECTION_NODE)
            && !variablesContext.isEmpty()) {
            // 解析 select|insert|update|delete 中文本内容或其子元素的文本内容或 sql 节点中的文本内容
            // replace variables in text node
            source.setNodeValue(PropertyParser.parse(source.getNodeValue(), variablesContext));
        }
    }

    /**
     * 获取 /mapper/sql 节点
     *
     * @param refid
     * @param variables
     * @return
     */
    private Node findSqlFragment(String refid, Properties variables) {
        refid = PropertyParser.parse(refid, variables);
        refid = builderAssistant.applyCurrentNamespace(refid, true);
        try {
            XNode nodeToInclude = configuration.getSqlFragments().get(refid);
            return nodeToInclude.getNode().cloneNode(true);
        } catch (IllegalArgumentException e) {
            throw new IncompleteElementException("Could not find SQL statement to include with refid '" + refid + "'", e);
        }
    }

    /**
     * 获取节点属性
     *
     * @param node
     * @param name
     * @return
     */
    private String getStringAttribute(Node node, String name) {
        return node.getAttributes().getNamedItem(name).getNodeValue();
    }

    /**
     * 合并 /mapper/select|update|insert|delete 节点中 include 节点的变量和 xml 配置文件中的变量
     * <p>
     * Read placeholders and their values from include node definition.
     *
     * @param node                      include 节点
     *                                  Include node instance
     * @param inheritedVariablesContext xml 配置文件中的属性
     *                                  Current context used for replace variables in new variables values
     * @return variables context from include instance (no inherited values)
     */
    private Properties getVariablesContext(Node node, Properties inheritedVariablesContext) {
        Map<String, String> declaredProperties = null;
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            // include 节点中的 property 节点
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                String name = getStringAttribute(n, "name");
                // Replace variables inside
                String value = PropertyParser.parse(getStringAttribute(n, "value"), inheritedVariablesContext);
                if (declaredProperties == null) {
                    declaredProperties = new HashMap<>();
                }
                if (declaredProperties.put(name, value) != null) {
                    throw new BuilderException("Variable " + name + " defined twice in the same include definition");
                }
            }
        }
        if (declaredProperties == null) {
            return inheritedVariablesContext;
        } else {
            Properties newProperties = new Properties();
            newProperties.putAll(inheritedVariablesContext);
            newProperties.putAll(declaredProperties);
            return newProperties;
        }
    }

}
