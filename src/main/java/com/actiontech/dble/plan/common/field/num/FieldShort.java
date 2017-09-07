package com.actiontech.dble.plan.common.field.num;

import com.actiontech.dble.plan.common.item.FieldTypes;
import com.actiontech.dble.plan.common.item.Item.ItemResult;

/**
 * smallint(%d) |unsigned |zerofilled
 *
 * @author ActionTech
 */
public class FieldShort extends FieldNum {

    public FieldShort(String name, String table, int charsetIndex, int fieldLength, int decimals, long flags) {
        super(name, table, charsetIndex, fieldLength, decimals, flags);
    }

    @Override
    public ItemResult resultType() {
        return ItemResult.INT_RESULT;
    }

    @Override
    public FieldTypes fieldType() {
        return FieldTypes.MYSQL_TYPE_SHORT;
    }

}