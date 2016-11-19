/*
 * Copyright 2016-2016 the original author or authors.
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
package org.hellojavaer.ddr.expression.simple;

import org.hellojavaer.ddr.expression.FormatExpression;
import org.hellojavaer.ddr.expression.FormatExpressionContext;
import org.hellojavaer.ddr.expression.FormatExpressionParser;
import org.hellojavaer.ddr.expression.ast.sytax.CompoundExpression;
import org.hellojavaer.ddr.expression.ast.sytax.FeSytaxParser;

/**
 *
 * @author <a href="mailto:hellojavaer@gmail.com">zoukaiming[邹凯明]</a>,created on 15/11/2016.
 */
public class SimpleFormatExpressionParser implements FormatExpressionParser {

    public FormatExpression parse(String str) {
        final CompoundExpression compoundExpression = FeSytaxParser.parse(str);
        FormatExpression formatExpression = new FormatExpression() {
            @Override
            public String getValue(FormatExpressionContext context) {
                return compoundExpression.getValue(context);
            }
        };
        return formatExpression;
    }
}
