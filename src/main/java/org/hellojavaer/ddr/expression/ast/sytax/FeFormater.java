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
package org.hellojavaer.ddr.expression.ast.sytax;

import org.hellojavaer.ddr.expression.FormatExpressionContext;
import org.hellojavaer.ddr.expression.ast.token.FeToken;
import org.hellojavaer.ddr.expression.ast.token.FeTokenType;
import org.hellojavaer.ddr.text.StringFormat;

import java.util.List;

/**
 *
 * @author <a href="mailto:hellojavaer@gmail.com">zoukaiming[邹凯明]</a>,created on 17/11/2016.
 */
public class FeFormater extends FeNodeImpl {

    private List<StringFormat> formats;
    private FeToken            token;

    public FeFormater(FeToken token, List<StringFormat> formats) {
        this.token = token;
        this.formats = formats;
    }

    @Override
    public String getValue(FormatExpressionContext context) {
        String from = null;
        if (token.getType() == FeTokenType.VAR) {
            Object obj = context.getVariable((String) token.getData());
            if (obj != null) {
                from = obj.toString();
            }
        } else {
            Object data = token.getData();
            if (data != null) {
                from = data.toString();
            }
        }
        if (from == null) {
            return "";
        }
        if (formats == null || formats.size() == 0) {
            return from;
        } else {
            for (StringFormat f : formats) {
                from = f.formt(from);
            }
            if (from != null) {
                return from;
            } else {
                return "";
            }
        }
    }

}
