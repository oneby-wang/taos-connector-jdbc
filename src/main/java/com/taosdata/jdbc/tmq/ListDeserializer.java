package com.taosdata.jdbc.tmq;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ListDeserializer implements Deserializer<List<Object>> {
    @Override
    public List<Object> deserialize(ResultSet data, String topic, String dbName) throws SQLException {
        ResultSetMetaData metaData = data.getMetaData();
        List<Object> list = new ArrayList<>(metaData.getColumnCount());

        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            list.add(data.getObject(i));
        }

        return list;
    }
}
