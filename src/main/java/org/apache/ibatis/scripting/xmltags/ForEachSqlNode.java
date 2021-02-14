/**
 * Copyright 2009-2019 the original author or authors.
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
package org.apache.ibatis.scripting.xmltags;

import java.util.Map;

import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.session.Configuration;

/**
 * foreach 节点
 *
 * @author Clinton Begin
 */
public class ForEachSqlNode implements SqlNode {

    /**
     * foreach 解析的参数前缀
     */
    public static final String ITEM_PREFIX = "__frch_";

    private final ExpressionEvaluator evaluator;

    /**
     * foreach 节点 collection 属性
     */
    private final String collectionExpression;

    /**
     * foreach 复合节点
     */
    private final SqlNode contents;

    // foreach 节点其他属性
    private final String open;
    private final String close;
    private final String separator;
    private final String item;
    private final String index;

    /**
     * mybatis 配置
     */
    private final Configuration configuration;

    public ForEachSqlNode(Configuration configuration, SqlNode contents, String collectionExpression, String index, String item, String open, String close, String separator) {
        this.evaluator = new ExpressionEvaluator();
        this.collectionExpression = collectionExpression;
        this.contents = contents;
        this.open = open;
        this.close = close;
        this.separator = separator;
        this.index = index;
        this.item = item;
        this.configuration = configuration;
    }

    /**
     * 解析 foreach 节点，将 foreach 每次迭代的内容设置为 context 中的参数，然后将内容替换为 #{} 形式，
     *
     * @param context
     * @return
     */
    @Override
    public boolean apply(DynamicContext context) {
        Map<String, Object> bindings = context.getBindings();
        // 从参数中获取集合或数组的可迭代对象
        final Iterable<?> iterable = evaluator.evaluateIterable(collectionExpression, bindings);
        if (!iterable.iterator().hasNext()) {
            return true;
        }
        boolean first = true;
        // 追加前缀
        applyOpen(context);
        int i = 0;
        for (Object o : iterable) {
            DynamicContext oldContext = context;
            if (first || separator == null) {
                context = new PrefixedContext(context, "");
            } else {
                context = new PrefixedContext(context, separator);
            }
            int uniqueNumber = context.getUniqueNumber();
            // Issue #709
            if (o instanceof Map.Entry) {
                @SuppressWarnings("unchecked")
                Map.Entry<Object, Object> mapEntry = (Map.Entry<Object, Object>) o;
                applyIndex(context, mapEntry.getKey(), uniqueNumber);
                applyItem(context, mapEntry.getValue(), uniqueNumber);
            } else {
                applyIndex(context, i, uniqueNumber);
                applyItem(context, o, uniqueNumber);
            }
            // 将 foreach 节点的内容替换为 #{} 参数形式
            contents.apply(new FilteredDynamicContext(configuration, context, index, item, uniqueNumber));
            if (first) {
                // 没有添加过前缀 first 则为 true
                first = !((PrefixedContext) context).isPrefixApplied();
            }
            context = oldContext;
            i++;
        }
        // 追加后缀
        applyClose(context);
        // 移除临时 item 和 index
        context.getBindings().remove(item);
        context.getBindings().remove(index);
        return true;
    }

    /**
     * @param context
     * @param o
     * @param i
     */
    private void applyIndex(DynamicContext context, Object o, int i) {
        if (index != null) {
            // 设置临时 index 的值
            context.bind(index, o);
            context.bind(itemizeItem(index, i), o);
        }
    }

    private void applyItem(DynamicContext context, Object o, int i) {
        if (item != null) {
            // 设置临时 item 的值
            context.bind(item, o);
            context.bind(itemizeItem(item, i), o);
        }
    }

    /**
     * sql 追加 open 属性指定的值
     *
     * @param context
     */
    private void applyOpen(DynamicContext context) {
        if (open != null) {
            context.appendSql(open);
        }
    }

    /**
     * sql 追加 close 属性指定的值
     *
     * @param context
     */
    private void applyClose(DynamicContext context) {
        if (close != null) {
            context.appendSql(close);
        }
    }

    /**
     * 添加前缀
     *
     * @param item
     * @param i
     * @return
     */
    private static String itemizeItem(String item, int i) {
        return ITEM_PREFIX + item + "_" + i;
    }

    /**
     *
     */
    private static class FilteredDynamicContext extends DynamicContext {

        /**
         * 被代理的上下文
         */
        private final DynamicContext delegate;

        /**
         * 表示当前迭代的累加值
         */
        private final int index;

        /**
         * 当前迭代的索引值
         */
        private final String itemIndex;

        /**
         * 当前迭代的项
         */
        private final String item;

        public FilteredDynamicContext(Configuration configuration, DynamicContext delegate, String itemIndex, String item, int i) {
            super(configuration, null);
            this.delegate = delegate;
            this.index = i;
            this.itemIndex = itemIndex;
            this.item = item;
        }

        @Override
        public Map<String, Object> getBindings() {
            return delegate.getBindings();
        }

        @Override
        public void bind(String name, Object value) {
            delegate.bind(name, value);
        }

        @Override
        public String getSql() {
            return delegate.getSql();
        }

        @Override
        public void appendSql(String sql) {
            // foreach 节点每次迭代将 foreach 中的文本设置为参数 #{}
            GenericTokenParser parser = new GenericTokenParser("#{", "}", content -> {
                String newContent = content.replaceFirst("^\\s*" + item + "(?![^.,:\\s])", itemizeItem(item, index));
                if (itemIndex != null && newContent.equals(content)) {
                    newContent = content.replaceFirst("^\\s*" + itemIndex + "(?![^.,:\\s])", itemizeItem(itemIndex, index));
                }
                return "#{" + newContent + "}";
            });

            delegate.appendSql(parser.parse(sql));
        }

        @Override
        public int getUniqueNumber() {
            return delegate.getUniqueNumber();
        }

    }

    /**
     * 包含 sql 前缀的上下文，追加 sql 片段时先追加前缀
     */
    private class PrefixedContext extends DynamicContext {

        /**
         * 被代理的上下文
         */
        private final DynamicContext delegate;

        /**
         * 前缀
         */
        private final String prefix;

        /**
         * 是否添加过前缀
         */
        private boolean prefixApplied;

        public PrefixedContext(DynamicContext delegate, String prefix) {
            super(configuration, null);
            this.delegate = delegate;
            this.prefix = prefix;
            this.prefixApplied = false;
        }

        public boolean isPrefixApplied() {
            return prefixApplied;
        }

        @Override
        public Map<String, Object> getBindings() {
            return delegate.getBindings();
        }

        @Override
        public void bind(String name, Object value) {
            delegate.bind(name, value);
        }

        @Override
        public void appendSql(String sql) {
            if (!prefixApplied && sql != null && sql.trim().length() > 0) {
                // 先添加前缀
                delegate.appendSql(prefix);
                prefixApplied = true;
            }
            delegate.appendSql(sql);
        }

        @Override
        public String getSql() {
            return delegate.getSql();
        }

        @Override
        public int getUniqueNumber() {
            return delegate.getUniqueNumber();
        }
    }

}
